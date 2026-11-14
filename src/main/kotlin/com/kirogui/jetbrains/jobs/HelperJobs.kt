package com.kirogui.jetbrains.jobs

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.kirogui.jetbrains.mcp.McpCall
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.argInt
import com.kirogui.jetbrains.mcp.argString
import com.kirogui.jetbrains.service.KiroAcpService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * `debug_task`: the main chat hands a debugging investigation to a separate Kiro session that runs on a cheaper model
 * and has the IDE tools only. The many small calls of a debugging session (breakpoint, run, look, step, look) then
 * cost the helper's price, and the main chat gets one short report, as a job that ends like any other.
 */
class HelperJobs(private val project: Project, private val jobs: JobService) {

    private val log = Logger.getInstance(HelperJobs::class.java)

    fun tools(): List<McpTool> = listOf(
        McpTool(
            name = "debug_task",
            description = "Hand a debugging investigation to a helper session on a cheaper model (it uses the IDE debugger: " +
                "sets breakpoints, runs, steps, evaluates) and get a short report back. Returns at once with a job id; the " +
                "IDE sends you the report when the helper is done, so do not poll or wait. Use it for open-ended " +
                "hunts (\"why is x wrong after the third request?\"); for one quick look, call debug_run_until yourself. " +
                "The helper cannot edit files or run commands: it reports the cause and a suggested fix, you apply it.",
            inputSchema = Schema.obj(
                listOf("goal"),
                "goal" to Schema.string("What to find out, with the symptom (expected vs actual) and how to reproduce it."),
                "context" to Schema.string("What you already know: suspect files and lines, what you ruled out, the run configuration or test to use."),
                "max_minutes" to Schema.integer("Give up after this many minutes (1-20, default 8)."),
            ),
            call = ::task,
        ),
    )

    private fun task(call: McpCall): McpResult {
        val goal = call.arguments.argString("goal") ?: return McpResult("`goal` is required.", isError = true)
        if (jobs.isHelper(call.sessionId)) return McpResult("A helper session can't start another helper; do the debugging yourself.", isError = true)
        val context = call.arguments.argString("context")
        val minutes = (call.arguments.argInt("max_minutes") ?: DEFAULT_MINUTES).coerceIn(1, 20)
        val sessionId = call.sessionId

        val job = jobs.newJob(JobKind.TASK, "debug_task: " + goal.lineSequence().first().take(48), goal, sessionId)
        job.state = JobState.RUNNING
        val cancelled = AtomicBoolean(false)
        var cancel: (() -> Unit)? = null
        job.stopper = {
            cancelled.set(true)
            cancel?.invoke()
        }
        jobs.changed()

        ApplicationManager.getApplication().executeOnPooledThread {
            val service = project.getService(KiroAcpService::class.java)
            try {
                val result = service.runHelper(
                    HelperPrompt.compose(goal, context), minutes * 60L,
                    onSession = { job.helperSessionId = it },
                    progress = { line ->
                        job.output.append(line + "\n")
                        jobs.changed()
                    },
                ) { cancelFn ->
                    cancel = cancelFn
                    if (cancelled.get()) cancelFn()
                }
                finish(job, result)
            } catch (e: Throwable) {
                log.warn("The debug helper failed", e)
                job.error = e.cause?.message ?: e.message ?: e.javaClass.simpleName
                job.end(JobState.FAILED, null)
                job.report = "Debug helper #${job.id} failed: ${job.error}. Debug it yourself with debug_run_until."
                done(job)
            }
        }
        return McpResult(
            "Started debug helper job #${job.id}. It investigates on a cheaper model (up to $minutes min) and the IDE sends " +
                "you its report when it is done; do not poll, sleep or wait. Meanwhile you can do other work or end your turn.",
        )
    }

    private fun finish(job: Job, result: KiroAcpService.HelperResult) {
        val cost = buildString {
            result.model?.let { append(it) }
            if (result.credits > 0) append(if (isEmpty()) "" else ", ").append("%.2f credits".format(result.credits))
        }
        job.output.append("\n--- report ---\n")
        job.output.append(result.text.ifBlank { "(the helper wrote no report)" })
        val stopped = job.stoppedByKiro.get() || job.stoppedByUser.get()
        when {
            stopped -> job.end(JobState.STOPPED, null)
            result.stopReason == "timeout" -> {
                job.summary = "timed out"
                job.end(JobState.FAILED, null)
            }
            else -> {
                job.summary = "report ready" + if (cost.isNotEmpty()) " ($cost)" else ""
                job.end(JobState.FINISHED, 0)
            }
        }
        job.report = buildString {
            append("Debug helper job #${job.id} ")
            append(
                when {
                    stopped -> "was stopped."
                    result.stopReason == "timeout" -> "ran out of time."
                    else -> "is done."
                },
            )
            if (cost.isNotEmpty()) append(" ($cost)")
            append("\ntask: ").append(job.detail.lineSequence().first().take(200)).append("\n\n")
            append(result.text.ifBlank { "It wrote no report. Debug it yourself with debug_run_until." })
        }
        done(job)
    }

    private fun done(job: Job) {
        jobs.changed()
        if (!job.stoppedByKiro.get()) jobs.ended(job)
    }

    companion object {
        const val DEFAULT_MINUTES = 8
    }
}

/** What the helper session is told. */
object HelperPrompt {
    fun compose(goal: String, context: String?): String = buildString {
        append("You are a debugging helper working for another Kiro session. Investigate the task below and report back. ")
        append("Do not change any files and do not run shell commands (both are refused).\n\n")
        append("TASK:\n").append(goal.trim()).append("\n")
        if (!context.isNullOrBlank()) append("\nWHAT IS KNOWN:\n").append(context.trim()).append("\n")
        append(
            """
HOW:
- Start with debug_run_until: breakpoints + run + the first stop with stack and variables, in one call. Pick the
  breakpoints from the symptom; read code with file_structure / find_definition / find_references, not whole files.
- Then debug_eval, debug_state and debug_control (step_over, step_into, step_out, resume, run_to_line). If the program has not
  stopped yet, debug_control with action=wait waits for it.
- A breakpoint condition may only use names that exist at that line (variables, parameters, fields); read the source for them.
  A member of another object is `item.quantity()` or `item.quantity`, never a bare `quantity`. If you are not sure, set no
  condition and use debug_eval after it stops: a condition that can't be evaluated opens a blocking error dialog for the user.
- Work in as few tool calls as you can (about 15 at most).
- When done, stop the program with debug_control action=stop.

FINISH with a report of at most 15 lines: the cause with file:line and the variable values that show it, what you
tried, and the change you suggest. If you could not find it, say what you ruled out and what to try next.
""".trimEnd(),
        )
    }
}
