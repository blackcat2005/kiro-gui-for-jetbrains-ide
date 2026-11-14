package com.kirogui.jetbrains.jobs

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.kirogui.jetbrains.mcp.McpCall
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.argBool
import com.kirogui.jetbrains.mcp.argInt
import com.kirogui.jetbrains.mcp.argString
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The console output of every program the IDE runs or debugs, the user's runs included, so Kiro can read an app's
 * log, a server's stack trace or a test run's output. Kept from the time the project opened; the last [KEEP] runs.
 */
class RunLogs(
    private val project: Project,
    parent: Disposable,
    private val reader: LogReader,
    /** The Kiro job (debug_start) a run belongs to, if any. */
    private val jobOf: (ProcessHandler) -> Job?,
    /** A Kiro job by id, so a bg_run job's output reads like a run's (same filters, same "new since last read" cursor as job_status). */
    private val findJob: (Int) -> Job? = { null },
) {

    class Run(val id: Int, val name: String, val mode: String, val handler: ProcessHandler, val startedAt: Long = System.currentTimeMillis()) {
        val output = OutputBuffer()
        @Volatile var exitCode: Int? = null
        @Volatile var endedAt: Long? = null
        val running: Boolean get() = endedAt == null && !handler.isProcessTerminated
    }

    private val runs = CopyOnWriteArrayList<Run>()
    private val nextId = AtomicInteger(1)

    init {
        project.messageBus.connect(parent).subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = track(executorId, env, handler)
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = track(executorId, env, handler)
        })
    }

    fun all(): List<Run> = runs.toList()

    @Synchronized
    private fun track(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
        if (runs.any { it.handler === handler }) return
        val run = Run(nextId.getAndIncrement(), env.runProfile.name, executorId.lowercase(), handler)
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = run.output.append(event.text)

            override fun processTerminated(event: ProcessEvent) {
                run.exitCode = event.exitCode
                run.endedAt = System.currentTimeMillis()
            }
        })
        runs.add(run)
        while (runs.size > KEEP) runs.firstOrNull { !it.running }?.let { runs.remove(it) } ?: break
    }

    fun tools(): List<McpTool> = listOf(
        McpTool(
            name = "run_logs",
            description = "Read the console output of programs run or debugged in the IDE (Run and Debug tool windows), " +
                "including ones the user started: application logs, server output, stack traces, test output. " +
                "Without `run` it lists the recent runs. Use `contains` to pick matching lines out of a long log. " +
                "A bg_run job is read too: pass `job` (its id). " + JobService.READ_HINT,
            inputSchema = Schema.obj(
                emptyList(),
                "run" to Schema.string("Run id (as listed) or run configuration name; the latest run with that name."),
                "job" to Schema.integer("A background job's id (bg_run, debug_start): read its output instead of a run's."),
                "lines" to Schema.integer("How many lines to show (default 80, at most 2000)."),
                "contains" to Schema.string("Only lines containing this text (any case)."),
                "new_only" to Schema.boolean("Only output printed since you last read this run."),
                "from_line" to Schema.integer("Read forward from this line number."),
            ),
            call = ::logs,
        ),
    )

    private fun logs(call: McpCall): McpResult {
        val lines = (call.arguments.argInt("lines") ?: 80).coerceIn(1, 2000)
        val contains = call.arguments.argString("contains")
        val wanted = call.arguments.argString("run")?.trim()?.removePrefix("#")
        val known = runs.toList()
        // "job 6", "job:6" and `job: 6` all mean a Kiro job, not a run.
        val jobId = call.arguments.argInt("job") ?: wanted?.let { Regex("""(?i)job\s*[:#]?\s*(\d+)""").matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        if (jobId != null) {
            val job = findJob(jobId) ?: return McpResult("There is no job #$jobId.", isError = true)
            val log = reader.read(
                call.sessionId, "job-$jobId", "Job #$jobId ${job.title} (${job.state.name.lowercase()})", job.output.numbered(), lines,
                call.arguments.argInt("from_line")?.toLong(), call.arguments.argBool("new_only") ?: false, contains,
            )
            return McpResult(log)
        }
        if (wanted == null) return McpResult(listing(known))

        val run = wanted.toIntOrNull()?.let { id -> known.firstOrNull { it.id == id } }
            ?: known.lastOrNull { it.name == wanted } ?: known.lastOrNull { it.name.equals(wanted, ignoreCase = true) }
        val fromLine = call.arguments.argInt("from_line")?.toLong()
        val newOnly = call.arguments.argBool("new_only") ?: false
        val (key, title, log) = if (run != null) {
            Triple("run-${run.id}", "Run #${run.id} ${run.name} (${run.mode}, ${state(run)}${jobNote(run)})", run.output.numbered())
        } else {
            // A console that was open before the plugin started watching.
            val console = consoleText(wanted) ?: return McpResult("No run \"$wanted\".\n" + listing(known), isError = true)
            Triple("console-${wanted.lowercase()}", "Console \"$wanted\"", NumberedLines.of(console))
        }
        return McpResult(reader.read(call.sessionId, key, title, log, lines, fromLine, newOnly, contains))
    }

    private fun jobNote(run: Run): String = jobOf(run.handler)?.let { ", Kiro's debug job #${it.id}" } ?: ""

    private fun listing(known: List<Run>): String {
        val untracked = untrackedConsoles(known)
        if (known.isEmpty() && untracked.isEmpty()) return "Nothing has been run in the IDE since the project was opened."
        val rows = known.sortedWith(compareByDescending<Run> { it.running }.thenByDescending { it.id }).map {
            "#${it.id} ${it.name} (${it.mode}, ${state(it)}, ${it.output.lineCount} lines${jobNote(it)})"
        } + untracked.map { "\"$it\" (open console from before the plugin was watching)" }
        return "Runs:\n" + rows.joinToString("\n") { "  $it" }
    }

    private fun state(run: Run): String = when {
        run.running -> "running for ${JobText.duration(System.currentTimeMillis() - run.startedAt)}"
        else -> "ended, exit code ${run.exitCode ?: "?"}"
    }

    private fun untrackedConsoles(known: List<Run>): List<String> = try {
        RunContentManager.getInstance(project).allDescriptors
            .filter { d -> known.none { it.handler === d.processHandler } && d.executionConsole is ConsoleViewImpl }
            .map { it.displayName }
    } catch (_: Exception) {
        emptyList()
    }

    private fun consoleText(name: String): String? {
        val result = CompletableFuture<String?>()
        ApplicationManager.getApplication().invokeLater({
            result.complete(try {
                val descriptor = RunContentManager.getInstance(project).allDescriptors
                    .lastOrNull { it.displayName.equals(name, ignoreCase = true) }
                (descriptor?.executionConsole as? ConsoleViewImpl)?.let { console ->
                    console.flushDeferredText()
                    console.editor?.document?.text
                }
            } catch (_: Exception) {
                null
            })
        }, ModalityState.any())
        return try {
            result.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) {
            null
        }
    }

    private companion object {
        const val KEEP = 30
    }
}
