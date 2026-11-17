package com.kirogui.jetbrains.jobs

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.PsiLocation
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.PsiElementProcessor
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.PsiManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.WriteAction
import com.kirogui.jetbrains.service.readNow
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.XDebuggerUtil
import com.intellij.xdebugger.XSourcePosition
import com.intellij.xdebugger.breakpoints.SuspendPolicy
import com.intellij.xdebugger.breakpoints.XBreakpointManager
import com.intellij.xdebugger.breakpoints.XBreakpointProperties
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import com.intellij.xdebugger.breakpoints.XLineBreakpointType
import com.intellij.xdebugger.evaluation.EvaluationMode
import com.intellij.xdebugger.breakpoints.XBreakpoint
import com.intellij.xdebugger.breakpoints.XBreakpointType
import com.kirogui.jetbrains.settings.KiroSettings
import com.kirogui.jetbrains.mcp.McpCall
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.argBool
import com.kirogui.jetbrains.mcp.argInt
import com.kirogui.jetbrains.mcp.argString
import com.kirogui.jetbrains.mcp.argStrings
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * `debug_*` tools: Kiro runs a run configuration under the IDE debugger and is woken when the program stops at a
 * breakpoint or ends, instead of blocking on a "wait for pause" call. While it is paused Kiro reads the stack and
 * variables, evaluates expressions and steps. Works with any debugger built on the XDebugger API (Java, Kotlin,
 * Python, JavaScript, Go, ...).
 *
 * Who is driving decides who hears about a pause: after Kiro starts, resumes or steps the program, the next pause
 * wakes Kiro. Once the user steps or resumes in the IDE themselves, pauses are theirs until Kiro acts again.
 */
class DebugJobs(private val project: Project, private val jobs: JobService) {

    private enum class Owner { KIRO, USER }

    private class DebugRun(val job: Job) {
        @Volatile var env: ExecutionEnvironment? = null
        @Volatile var session: XDebugSession? = null
        @Volatile var handler: ProcessHandler? = null
        @Volatile var owner = Owner.KIRO
        val expectKiroResume = AtomicBoolean(false)
        val waiter = AtomicReference<CompletableFuture<String>?>(null)
        val attached = AtomicBoolean(false)
        val outputAttached = AtomicBoolean(false)
        val finished = AtomicBoolean(false)
        @Volatile var resumeSeen = false
        @Volatile var lastAction: String? = null

        val label: String get() = "Debug job #${job.id} (${job.detail})"
    }

    private class Target(val session: XDebugSession, val run: DebugRun?, val label: String)

    private val log = Logger.getInstance(DebugJobs::class.java)
    private val runs = ConcurrentHashMap<Int, DebugRun>()
    private val kiroBreakpoints: MutableSet<XLineBreakpoint<*>> =
        java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(java.util.WeakHashMap()))

    init {
        val bus = project.messageBus.connect(jobs)
        bus.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = started(env, handler)
            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) = started(env, handler)
            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                runFor(env)?.let { notStarted(it, null) }
            }
        })
        bus.subscribe(XDebuggerManager.TOPIC, object : XDebuggerManagerListener {
            override fun processStarted(debugProcess: XDebugProcess) {
                val handler = debugProcess.processHandler
                runs.values.firstOrNull { it.handler != null && it.handler === handler }?.let { attach(it, debugProcess.session) }
            }
        })
    }

    fun tools(): List<McpTool> = listOf(
        McpTool(
            name = "debug_start",
            description = "Run one of the project's run configurations under the IDE debugger and return at once with a " +
                "job id. Set breakpoints first with debug_breakpoint. When the program stops at a breakpoint you " +
                "automatically get a message with the location, stack and variables; when it ends you get the exit " +
                "code and output. Do not poll, sleep or wait: end your turn (for example after asking the user to " +
                "reproduce the problem in the running app). Pass `configuration` for a saved run configuration, or " +
                "`file` (+ `line`) to have the IDE create one for you, exactly like the gutter's Run/Debug icon " +
                "(a main class, a test, a Python or Node script...). Without either it lists the run configurations.",
            inputSchema = Schema.obj(
                emptyList(),
                "configuration" to Schema.string("Name of a saved run configuration."),
                "file" to Schema.string("Source file to run, relative to the project folder or absolute. Creates a temporary run configuration."),
                "line" to Schema.integer("Line inside the file (1-based) to pick the class, method or test to run; default: the file."),
            ),
            call = ::start,
        ),
        McpTool(
            name = "debug_run_until",
            description = "The whole debugging start in ONE call: sets the breakpoints, runs the program under the IDE " +
                "debugger and waits for the first stop, then returns the location, source, stack, variables and console. " +
                "Use it instead of debug_breakpoint + debug_start. `breakpoints` are strings like \"src/Foo.kt:42\" or " +
                "\"src/Foo.kt:42 if count > 3\". If the program has not stopped when `wait_seconds` is over (a slow build, " +
                "or it is waiting for the user), you get a message when it stops or ends; do not poll. Pass " +
                "`configuration` for a saved run configuration, or `file` (+ `line`) to have the IDE create one.",
            inputSchema = Schema.obj(
                emptyList(),
                "breakpoints" to Schema.stringArray("Line breakpoints, each \"file:line\" or \"file:line if <condition>\"."),
                "exceptions" to Schema.boolean("Also stop where an exception is thrown (turn off again with debug_exceptions)."),
                "configuration" to Schema.string("Name of a saved run configuration."),
                "file" to Schema.string("Source file to run, relative to the project folder or absolute. Creates a temporary run configuration."),
                "line" to Schema.integer("Line inside the file (1-based) to pick the class, method or test to run."),
                "wait_seconds" to Schema.integer("How long to wait for the first stop (5-55, default 40)."),
            ),
            call = ::runUntil,
        ),
        McpTool(
            name = "debug_breakpoint",
            description = "Add or remove a line breakpoint (shown in the IDE editor). With `log` it becomes a " +
                "tracepoint: the program does not stop, the expression's value is written to the job's output " +
                "A condition or log expression may only use variables, parameters and fields that exist at that line " +
                "(read the source first): if the debugger can't evaluate it, the IDE shows the user a blocking error dialog. " +
                "(read it with job_status, or in the message you get when the job ends). Breakpoints apply to " +
                "running debug sessions at once.",
            inputSchema = Schema.obj(
                listOf("file", "line"),
                "file" to Schema.string("Source file, relative to the project folder or absolute."),
                "line" to Schema.integer("Line number, 1-based."),
                "condition" to Schema.string("Only stop when this expression (in the program's language) is true."),
                "log" to Schema.string("Expression to log instead of stopping (tracepoint)."),
                "remove" to Schema.boolean("Remove the breakpoints on this line instead."),
                "force" to Schema.boolean("Set the breakpoint even though a name in the condition isn't declared in the file."),
            ),
            call = ::breakpoint,
        ),
        McpTool(
            name = "debug_exceptions",
            description = "Turn the debugger's exception breakpoints on or off (\"Any exception\" for Java and Kotlin, " +
                "Python exceptions, ...), so the program stops where an error is thrown without you knowing the line. " +
                "Without `enable` it only lists them. Turn them off again when you are done.",
            inputSchema = Schema.obj(emptyList(), "enable" to Schema.boolean("true to stop on exceptions, false to stop doing so.")),
            call = ::exceptions,
        ),
        McpTool(
            name = "debug_state",
            description = "Where the debugged program is: paused location with source, stack and variables, or that " +
                "it is running; plus the breakpoints. Uses your latest debug job, or the debug session the user has " +
                "open in the IDE.",
            inputSchema = Schema.obj(
                emptyList(),
                "job" to Schema.integer("Debug job id (default: the latest one, else the IDE's current debug session)."),
                "frame" to Schema.integer("Stack frame to show variables for (0 = top)."),
            ),
            call = ::state,
        ),
        McpTool(
            name = "debug_eval",
            description = "Evaluate an expression in the paused program (in its language), in the top frame or `frame`. " +
                "Shows the value and its fields. Expressions can call methods and change state, so prefer plain reads.",
            inputSchema = Schema.obj(
                listOf("expression"),
                "expression" to Schema.string("The expression."),
                "job" to Schema.integer("Debug job id (default: as debug_state)."),
                "frame" to Schema.integer("Stack frame (0 = top)."),
            ),
            call = ::eval,
        ),
        McpTool(
            name = "debug_control",
            description = "Step, resume, pause or stop the debugged program, or run_to_line (continue to a file and line without " +
                "leaving a breakpoint behind), or wait (just wait for the next stop). Waits up to `wait_seconds` for it to stop " +
                "again and then returns the new location, stack and variables; if it is still running after that " +
                "you get a message when it stops or ends, so do not poll.",
            inputSchema = Schema.obj(
                listOf("action"),
                "action" to Schema.string("What to do.", ACTIONS),
                "file" to Schema.string("For run_to_line: source file, relative to the project folder or absolute."),
                "line" to Schema.integer("For run_to_line: line number, 1-based."),
                "job" to Schema.integer("Debug job id (default: as debug_state)."),
                "wait_seconds" to Schema.integer("How long to wait for the next stop (0-30; default 10 for steps, 3 for resume; up to 55, default 30, for wait)."),
            ),
            call = ::control,
        ),
    )

    // ---------- debug_start ----------

    private fun start(call: McpCall): McpResult = launch(call, null).first

    /**
     * Starts the program under the debugger. [waiter], when given, is installed before the launch so the first stop
     * completes it. The run is null when nothing was started (an error, or the list of configurations).
     */
    private fun launch(call: McpCall, waiter: CompletableFuture<String>?): Pair<McpResult, DebugRun?> {
        val name = call.arguments.argString("configuration")
        val file = call.arguments.argString("file")
        val all = RunManager.getInstance(project).allSettings
        val settings = name?.let { n -> all.firstOrNull { it.name == n } ?: all.firstOrNull { it.name.equals(n, ignoreCase = true) } }
            ?: if (name == null && file != null) {
                try {
                    configurationFor(file, call.arguments.argInt("line"))
                } catch (e: Exception) {
                    return McpResult(e.message ?: "Can't run $file.", isError = true) to null
                }
            } else {
                return McpResult(
                    (if (name != null) "There is no run configuration named \"$name\".\n" else "") + configurations(all),
                    isError = name != null,
                ) to null
            }
        val job = jobs.newJob(JobKind.DEBUG, settings.name, settings.name, call.sessionId)
        val run = DebugRun(job)
        waiter?.let { run.waiter.set(it) }
        runs[job.id] = run
        job.stopper = { stopRun(run) }
        jobs.changed()

        val launched = CompletableFuture<String?>() // null = launched, else why not
        ApplicationManager.getApplication().invokeLater({
            try {
                val builder = ExecutionEnvironmentBuilder.createOrNull(DefaultDebugExecutor.getDebugExecutorInstance(), settings)
                    ?: throw IllegalStateException("\"${settings.name}\" can't be run under the debugger.")
                val env = builder.build(object : ProgramRunner.Callback {
                    override fun processStarted(descriptor: RunContentDescriptor?) {
                        descriptor ?: return
                        val session = XDebuggerManager.getInstance(project).debugSessions.firstOrNull { runCatching { it.debugProcess.processHandler }.getOrNull().let { h -> h != null && h === descriptor.processHandler } }
                        if (session != null) attach(run, session)
                        else descriptor.processHandler?.let { attachOutput(run, it) }
                    }

                    override fun processNotStarted(error: Throwable?) = notStarted(run, error?.message)
                })
                run.env = env
                ProgramRunnerUtil.executeConfiguration(env, false, true)
                launched.complete(null)
            } catch (e: Exception) {
                launched.complete(e.message ?: e.javaClass.simpleName)
            }
        }, ModalityState.defaultModalityState())
        val error = try {
            launched.get(15, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            null // the IDE is busy; the launch is still queued
        }
        if (error != null) {
            notStarted(run, error, wake = false)
            return McpResult("Could not start \"${settings.name}\" under the debugger: $error", isError = true) to null
        }
        val breakpoints = lineBreakpoints().filter { it.isEnabled }
        return McpResult(
            "Started ${run.label}. The IDE builds and launches it (Debug tool window). You will get a message when it " +
                "stops at a breakpoint or ends; do not poll, sleep or wait.\n" +
                if (breakpoints.isEmpty()) "No breakpoints are set yet, so it will only stop if you add some with debug_breakpoint."
                else "Breakpoints:\n" + breakpoints.joinToString("\n") { "  " + describe(it) },
        ) to run
    }

    // ---------- debug_run_until ----------

    private val breakpointSpec = Regex("""^\s*(.+?):(\d+)(?:\s+if\s+(.+))?\s*$""")

    private fun runUntil(call: McpCall): McpResult {
        val sessionId = call.sessionId
        val specs = call.arguments.argStrings("breakpoints")
        val set = mutableListOf<String>()
        for (spec in specs) {
            val m = breakpointSpec.matchEntire(spec)
                ?: return McpResult("Can't read the breakpoint \"$spec\"; write it as file:line or file:line if condition.", isError = true)
            val args = kotlinx.serialization.json.buildJsonObject {
                put("file", kotlinx.serialization.json.JsonPrimitive(m.groupValues[1]))
                put("line", kotlinx.serialization.json.JsonPrimitive(m.groupValues[2].toInt()))
                m.groups[3]?.value?.let { put("condition", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            val r = breakpoint(McpCall(sessionId, args))
            if (r.isError) return McpResult("Nothing was started. ${r.text}", isError = true)
            set.add(r.text)
        }
        if (call.arguments.argBool("exceptions") == true) {
            val r = exceptions(McpCall(sessionId, kotlinx.serialization.json.buildJsonObject {
                put("enable", kotlinx.serialization.json.JsonPrimitive(true))
            }))
            if (!r.isError) set.add("Exception breakpoints on.")
        }
        val waiter = CompletableFuture<String>()
        val (started, run) = launch(call, waiter)
        if (run == null) return started
        val seconds = (call.arguments.argInt("wait_seconds") ?: 40).coerceIn(5, 55)
        val head = if (set.isEmpty()) "" else set.joinToString("\n") + "\n"
        val result = awaitStop(run, waiter, seconds, sessionId)
        return McpResult(head + result.text, result.isError)
    }

    /** Waits for [waiter] (the next stop or end of [run]); on timeout says how the caller will hear about it. */
    private fun awaitStop(run: DebugRun, waiter: CompletableFuture<String>, seconds: Int, sessionId: String?): McpResult = try {
        McpResult(waiter.get(seconds.toLong(), TimeUnit.SECONDS))
    } catch (_: TimeoutException) {
        if (run.waiter.compareAndSet(waiter, null)) {
            val state = if (run.job.state == JobState.STARTING) "is still being built and launched" else "has not stopped yet"
            McpResult(
                "${run.label} $state after ${seconds}s. " + if (jobs.isHelper(sessionId)) {
                    "Call debug_control with action=wait to wait for the next stop."
                } else {
                    "You will get a message when it stops or ends; do not poll, sleep or wait."
                },
            )
        } else {
            McpResult(waiter.get(FRAMES_TIMEOUT_MS + VARIABLES_TIMEOUT_MS, TimeUnit.MILLISECONDS))
        }
    } catch (e: ExecutionException) {
        McpResult("Could not wait: ${e.cause?.message ?: e.message}", isError = true)
    }

    /** debug_control action=wait: no step, only wait for the program to stop or end. */
    private fun waitForStop(call: McpCall): McpResult {
        val run = runs.values.filter { it.job.sessionId == call.sessionId && !it.finished.get() }.maxByOrNull { it.job.id }
            ?: return McpResult("There is no running debug job to wait for.", isError = true)
        val session = run.session
        if (session != null && session.isSuspended) return McpResult(snapshot(session, run.label, 0, run.lastAction, newConsole(run, CONSOLE_LINES)))
        val waiter = CompletableFuture<String>()
        run.waiter.set(waiter)
        run.owner = Owner.KIRO
        if (run.finished.get()) {
            run.waiter.compareAndSet(waiter, null)
            return McpResult("${run.label} already ended (${JobText.outcome(run.job)}).\n" + run.job.output.tail(20))
        }
        val seconds = (call.arguments.argInt("wait_seconds") ?: 30).coerceIn(1, 55)
        return awaitStop(run, waiter, seconds, call.sessionId)
    }

    /** The run configuration the IDE would make for the gutter's Run icon at [path]:[line], kept as a temporary one. */
    private fun configurationFor(path: String, line: Int?): RunnerAndConfigurationSettings {
        val base = project.basePath ?: System.getProperty("user.home")
        val io = if (File(path).isAbsolute) File(path) else File(base, path)
        val vfile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
            ?: throw IllegalArgumentException("File not found: $path")
        return onEdt {
            val psiFile = PsiManager.getInstance(project).findFile(vfile)
                ?: throw IllegalArgumentException("$path is not a source file the IDE understands.")
            val document = PsiDocumentManager.getInstance(project).getDocument(psiFile)
            val element = if (line != null && document != null && line in 1..document.lineCount) {
                val start = document.getLineStartOffset(line - 1)
                val text = document.charsSequence
                var offset = start
                while (offset < document.getLineEndOffset(line - 1) && text[offset].isWhitespace()) offset++
                psiFile.findElementAt(offset) ?: psiFile
            } else psiFile
            val context = ConfigurationContext.createEmptyContextForLocation(PsiLocation.fromPsiElement(element))
            val found = context.configuration
                ?: throw IllegalArgumentException(
                    "The IDE has no way to run $path${line?.let { ":$it" } ?: ""} (no main method, test or script there). " +
                        "Ask the user to create a run configuration, or try another line.",
                )
            val manager = RunManager.getInstance(project)
            if (manager.findSettings(found.configuration) == null) manager.setTemporaryConfiguration(found)
            found
        }
    }

    private fun configurations(all: List<RunnerAndConfigurationSettings>): String =
        if (all.isEmpty()) "The project has no run configurations. Ask the user to create one (Run > Edit Configurations)."
        else "Run configurations:\n" + all.joinToString("\n") { "  ${it.name} (${it.type.displayName})" }

    private fun runFor(env: ExecutionEnvironment): DebugRun? = runs.values.firstOrNull { it.env === env }

    private fun started(env: ExecutionEnvironment, handler: ProcessHandler) {
        val run = runFor(env) ?: return
        attachOutput(run, handler)
    }

    private fun attachOutput(run: DebugRun, handler: ProcessHandler) {
        run.handler = handler
        run.job.process = handler
        if (!run.outputAttached.compareAndSet(false, true)) return
        handler.addProcessListener(object : ProcessListener {
            // Everything, including the IDE's own lines: tracepoint values arrive as "system" text.
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) = run.job.output.append(event.text)

            override fun processTerminated(event: ProcessEvent) = ended(run, event.exitCode)
        })
        if (run.job.state == JobState.STARTING) {
            run.job.state = JobState.RUNNING
            jobs.changed()
        }
    }

    private fun attach(run: DebugRun, session: XDebugSession, reportCurrentPause: Boolean = true) {
        if (!run.attached.compareAndSet(false, true)) return
        run.session = session
        session.debugProcess.processHandler?.let { attachOutput(run, it) }
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() = paused(run, session)
            override fun beforeSessionResume() = resumed(run)
            override fun sessionResumed() = resumed(run)
            override fun sessionStopped() {
                val h = run.handler
                if (h == null || h.isProcessTerminated) ended(run, h?.exitCode)
            }
        })
        if (run.job.state == JobState.STARTING) run.job.state = JobState.RUNNING
        jobs.changed()
        // It may have stopped at a breakpoint before the listener was in place.
        if (session.isSuspended) {
            if (reportCurrentPause) paused(run, session) else run.job.state = JobState.PAUSED
        }
    }

    private fun notStarted(run: DebugRun, why: String?, wake: Boolean = true) {
        if (!run.finished.compareAndSet(false, true)) return
        run.job.error = why ?: "the launch was cancelled or the build failed"
        run.job.end(JobState.FAILED, null)
        run.waiter.getAndSet(null)?.complete("${run.label} did not start: ${run.job.error}.")
        jobs.changed()
        if (wake) jobs.ended(run.job)
    }

    // ---------- events ----------

    private fun resumed(run: DebugRun) {
        if (run.resumeSeen) return
        run.resumeSeen = true
        run.owner = if (run.expectKiroResume.getAndSet(false)) Owner.KIRO else Owner.USER
        if (run.owner == Owner.USER) run.lastAction = null
        if (run.job.state.active) run.job.state = JobState.RUNNING
        jobs.changed()
    }

    private fun paused(run: DebugRun, session: XDebugSession) {
        run.resumeSeen = false
        run.job.state = JobState.PAUSED
        jobs.changed()
        // A stop nobody will hear about (the user is stepping) is not read at all, so it doesn't use up console lines.
        val reported = run.waiter.get() != null ||
            (run.owner == Owner.KIRO && !run.job.stoppedByKiro.get() && run.job.sessionId != null)
        if (!reported) return
        val context = session.suspendContext
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = try {
                snapshot(session, run.label, 0, run.lastAction, newConsole(run, CONSOLE_LINES))
            } catch (e: Exception) {
                log.info("Could not describe the paused program", e)
                "${run.label} is paused (the details could not be read: ${e.message})."
            }
            val waiting = run.waiter.getAndSet(null)
            when {
                waiting != null -> waiting.complete(text)
                run.owner == Owner.KIRO && !run.job.stoppedByKiro.get() && run.job.sessionId != null -> jobs.post(
                    Notice(
                        run.job.sessionId,
                        "debug-${run.job.id}",
                        "${run.label} paused at ${where(session.currentPosition)}",
                        text + "\n" + PAUSED_FOOTER,
                        obsolete = { session.isStopped || !session.isSuspended || session.suspendContext !== context },
                    ),
                )
            }
        }
    }

    private fun ended(run: DebugRun, exitCode: Int?) {
        if (!run.finished.compareAndSet(false, true)) return
        val job = run.job
        val state = when {
            job.stoppedByKiro.get() || job.stoppedByUser.get() -> JobState.STOPPED
            exitCode == null || exitCode == 0 -> JobState.FINISHED
            else -> JobState.FAILED
        }
        job.end(state, exitCode)
        job.sessionId?.let { jobs.dropNotice(it, "debug-${job.id}") }
        run.waiter.getAndSet(null)?.complete("${run.label} ended (${JobText.outcome(job)}).\n" + job.output.tail(20))
        jobs.changed()
        if (!job.stoppedByKiro.get() && run.owner == Owner.KIRO) jobs.ended(job)
    }

    private fun stopRun(run: DebugRun) {
        val session = run.session
        ApplicationManager.getApplication().invokeLater({
            if (session != null && !session.isStopped) session.stop() else run.handler?.destroyProcess()
        }, ModalityState.any())
    }

    // ---------- reading the paused program ----------

    /**
     * What the program printed since Kiro last saw its console (in a stop, debug_state or job_status), the newest
     * [max] lines; null if nothing new, so a step that printed nothing doesn't repeat old lines.
     */
    private fun newConsole(run: DebugRun?, max: Int): String? {
        val job = run?.job ?: return null
        val (w, firstNew) = jobs.reader.newest(job.sessionId, "job-${job.id}", job.output.numbered(), max) ?: return null
        val fresh = w.last - firstNew + 1
        val head = if (w.first > firstNew) {
            "Console, newest ${w.shown} of $fresh new lines (job_status id=${job.id} from_line=$firstNew shows them all):"
        } else {
            "Console, new since you last saw it (lines ${w.first}-${w.last}):"
        }
        return head + "\n" + w.text
    }

    private fun snapshot(session: XDebugSession, label: String, frameIndex: Int, lastAction: String?, console: String? = null): String {
        val frames = DebugInspector.frames(session, MAX_FRAMES, FRAMES_TIMEOUT_MS)
        val selected = frames.getOrNull(frameIndex) ?: frames.firstOrNull()
        val position = selected?.frame?.sourcePosition ?: session.currentPosition
        val header = "$label paused at ${where(position)} — ${reason(position, lastAction)}."
        val source = position?.let { DebugInspector.sourceLines(it.file, it.line, 2) }
        val (variables, note) = selected?.let { DebugInspector.variables(it.frame, MAX_VARIABLES, VARIABLES_TIMEOUT_MS) }
            ?: (emptyList<VarLine>() to "(no stack frame)")
        return DebugText.snapshot(
            header = header,
            source = source,
            thread = DebugInspector.threadName(session),
            frames = frames.map { FrameLine(it.label, it.frame.sourcePosition?.let(::where)) },
            selected = selected?.index ?: 0,
            variables = variables,
            variablesNote = note,
            console = console,
        )
    }

    private fun reason(position: XSourcePosition?, lastAction: String?): String {
        val hits = position?.let { p -> lineBreakpoints().filter { it.isEnabled && it.fileUrl == p.file.url && it.line == p.line } }.orEmpty()
        val bp = hits.firstOrNull()
        return when {
            bp != null -> "line breakpoint" + (bp.conditionExpression?.expression?.takeIf { it.isNotBlank() }?.let { " (condition: $it)" } ?: "")
            lastAction != null && lastAction != "resume" -> "after $lastAction"
            else -> "paused (exception, pause, or a breakpoint of another kind)"
        }
    }

    private fun where(position: XSourcePosition?): String =
        position?.let { "${relative(it.file)}:${it.line + 1}" } ?: "an unknown location"

    private fun relative(file: VirtualFile): String {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/')
        val path = file.path
        return if (base != null && path.startsWith("$base/")) path.removePrefix("$base/") else path
    }

    // ---------- which session a tool means ----------

    private fun target(call: McpCall): Target? {
        val manager = XDebuggerManager.getInstance(project)
        call.arguments.argInt("job")?.let { id ->
            val run = runs[id] ?: return null
            val session = run.session ?: return null
            return Target(session, run, run.label)
        }
        if (runs.values.any { it.job.sessionId == call.sessionId && it.job.state == JobState.STARTING }) return null
        runs.values
            .filter { it.job.sessionId == call.sessionId && it.job.state.active && it.session != null }
            .maxByOrNull { it.job.id }
            ?.let { return Target(it.session!!, it, it.label) }
        val session = manager.currentSession?.takeIf { !it.isStopped } ?: manager.debugSessions.firstOrNull { !it.isStopped } ?: return null
        val run = runs.values.firstOrNull { it.session === session }
        return Target(session, run, run?.label ?: "The IDE's debug session \"${session.sessionName}\"")
    }

    private fun noTarget(call: McpCall): McpResult {
        val starting = runs.values.firstOrNull { it.job.sessionId == call.sessionId && it.job.state == JobState.STARTING }
        return if (starting != null) McpResult("${starting.label} is still being built and launched. You will get a message when it stops or ends.")
        else McpResult("There is no debug session. Start one with debug_start.", isError = true)
    }

    /** Kiro takes the wheel of a session the user started, so it hears about the next stop too. */
    private fun adopt(session: XDebugSession, call: McpCall): DebugRun {
        runs.values.firstOrNull { it.session === session }?.let { return it }
        val job = jobs.newJob(JobKind.DEBUG, session.sessionName, session.sessionName, call.sessionId)
        val run = DebugRun(job)
        run.owner = Owner.USER
        runs[job.id] = run
        job.stopper = { stopRun(run) }
        attach(run, session, reportCurrentPause = false)
        return run
    }

    // ---------- debug_state / debug_eval / debug_control ----------

    private fun state(call: McpCall): McpResult {
        val target = target(call) ?: return noTarget(call)
        val session = target.session
        val breakpoints = lineBreakpoints()
        val bpText = if (breakpoints.isEmpty()) "Breakpoints: none."
        else "Breakpoints:\n" + breakpoints.joinToString("\n") { "  " + describe(it) }
        if (!session.isSuspended) {
            val output = newConsole(target.run, 15)
            return McpResult("${target.label} is running (not paused).\n$bpText" + (output?.let { "\n$it" } ?: ""))
        }
        val frame = (call.arguments.argInt("frame") ?: 0).coerceAtLeast(0)
        val console = newConsole(target.run, CONSOLE_LINES)
        return McpResult(snapshot(session, target.label, frame, target.run?.lastAction, console) + "\n" + bpText)
    }

    private fun eval(call: McpCall): McpResult {
        val expression = call.arguments.argString("expression") ?: return McpResult("`expression` is required.", isError = true)
        val evalMode = KiroSettings.getInstance().state.evalMode
        EvalGuard.check(expression, evalMode)?.let { why -> return McpResult(EvalGuard.message(expression, evalMode, why), isError = true) }
        val target = target(call) ?: return noTarget(call)
        val session = target.session
        if (!session.isSuspended) return McpResult("${target.label} is running; it must be paused to evaluate.", isError = true)
        val index = (call.arguments.argInt("frame") ?: 0).coerceAtLeast(0)
        val frame = DebugInspector.frames(session, index + 1, FRAMES_TIMEOUT_MS).getOrNull(index)?.frame ?: session.currentStackFrame
        val value = try {
            DebugInspector.evaluate(session, frame, expression, EVAL_TIMEOUT_MS)
        } catch (e: Exception) {
            return McpResult("Could not evaluate `$expression`: ${e.message}", isError = true)
        }
        val deadline = System.currentTimeMillis() + VARIABLES_TIMEOUT_MS
        val shown = DebugInspector.present(listOf(expression to value), deadline).first()
        val text = StringBuilder(DebugText.value(shown))
        if (shown.hasChildren) {
            val (children, note) = DebugInspector.children(value, MAX_CHILDREN, VARIABLES_TIMEOUT_MS / 2)
            DebugInspector.present(children, deadline + VARIABLES_TIMEOUT_MS / 2).forEach { text.append("\n  ").append(DebugText.value(it)) }
            note?.let { text.append("\n  ").append(it) }
        }
        return McpResult(text.toString())
    }

    private fun control(call: McpCall): McpResult {
        val action = call.arguments.argString("action")?.lowercase()
            ?: return McpResult("`action` is required.", isError = true)
        if (action !in ACTIONS) return McpResult("Unknown action \"$action\". Use one of: ${ACTIONS.joinToString()}.", isError = true)
        if (action == "wait") return waitForStop(call)
        val target = target(call) ?: return noTarget(call)
        val session = target.session
        val run = target.run ?: adopt(session, call)
        val moves = action != "pause" && action != "stop"
        var runTo: XSourcePosition? = null
        if (action == "run_to_line") {
            val path = call.arguments.argString("file") ?: return McpResult("`file` is required for run_to_line.", isError = true)
            val line = call.arguments.argInt("line") ?: return McpResult("`line` is required for run_to_line.", isError = true)
            if (line < 1) return McpResult("`line` is 1-based.", isError = true)
            val base = project.basePath ?: System.getProperty("user.home")
            val io = if (File(path).isAbsolute) File(path) else File(base, path)
            val vfile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io) ?: return McpResult("File not found: $path", isError = true)
            runTo = XDebuggerUtil.getInstance().createPosition(vfile, line - 1) ?: return McpResult("Can't run to $path:$line.", isError = true)
        }
        if (moves && !session.isSuspended) {
            return McpResult("${run.label} is running, not paused, so it can't $action. Use pause, or wait for the next stop.", isError = true)
        }
        if (action == "pause" && session.isSuspended) {
            return McpResult(snapshot(session, run.label, 0, run.lastAction, newConsole(run, CONSOLE_LINES)))
        }
        if (action == "stop") {
            run.job.stoppedByKiro.set(true)
            run.job.sessionId?.let { jobs.dropNotice(it, "debug-${run.job.id}") }
            stopRun(run)
            return McpResult("Stopping ${run.label}.")
        }

        val waiter = CompletableFuture<String>()
        run.waiter.set(waiter)
        run.owner = Owner.KIRO
        run.lastAction = action
        if (moves) {
            run.expectKiroResume.set(true)
            run.job.sessionId?.let { jobs.dropNotice(it, "debug-${run.job.id}") }
        }
        ApplicationManager.getApplication().invokeLater({
            when (action) {
                "step_over" -> session.stepOver(false)
                "step_into" -> session.stepInto()
                "step_out" -> session.stepOut()
                "resume" -> session.resume()
                "pause" -> session.pause()
                "run_to_line" -> runTo?.let { session.runToPosition(it, false) }
            }
        }, ModalityState.defaultModalityState())

        val wait = (call.arguments.argInt("wait_seconds") ?: if (action == "resume") 3 else 10).coerceIn(0, 30)
        return try {
            McpResult(waiter.get(wait.toLong(), TimeUnit.SECONDS))
        } catch (_: TimeoutException) {
            if (run.waiter.compareAndSet(waiter, null)) {
                McpResult("${run.label} is running. You will get a message when it stops or ends; do not poll, sleep or wait.")
            } else {
                // The stop arrived just now and is being described.
                McpResult(waiter.get(FRAMES_TIMEOUT_MS + VARIABLES_TIMEOUT_MS, TimeUnit.MILLISECONDS))
            }
        } catch (e: ExecutionException) {
            McpResult("Could not $action: ${e.cause?.message ?: e.message}", isError = true)
        }
    }

    // ---------- debug_exceptions ----------

    @Suppress("UNCHECKED_CAST")
    private fun exceptionBreakpoints(): List<XBreakpoint<*>> = readNow {
        val manager = XDebuggerManager.getInstance(project).breakpointManager
        val types = XBreakpointType.EXTENSION_POINT_NAME.extensionList
            .filter { it !is XLineBreakpointType<*> && it.id.contains("exception", ignoreCase = true) }
        val defaults = types.flatMap { manager.getDefaultBreakpoints(it as XBreakpointType<XBreakpoint<XBreakpointProperties<*>>, XBreakpointProperties<*>>) }
        (manager.allBreakpoints.filter { it !is XLineBreakpoint<*> && it.type.id.contains("exception", ignoreCase = true) } + defaults).distinct()
    }

    private fun exceptions(call: McpCall): McpResult {
        val enable = call.arguments.argBool("enable")
        return onEdt {
            val list = exceptionBreakpoints()
            if (list.isEmpty()) return@onEdt McpResult("This IDE has no exception breakpoints for the project's languages.", isError = true)
            if (enable != null) WriteAction.run<RuntimeException> { list.forEach { it.isEnabled = enable } }
            val state = list.joinToString("\n") { "  ${it.type.title}: ${if (it.isEnabled) "on" else "off"}" }
            McpResult(
                if (enable == null) "Exception breakpoints:\n$state"
                else "Exception breakpoints are now ${if (enable) "on" else "off"}:\n$state" +
                    if (enable) "\nThe program stops where an exception is thrown. Turn them off again with enable=false when done." else "",
            )
        }
    }

    // ---------- debug_breakpoint ----------

    private fun breakpoint(call: McpCall): McpResult {
        val path = call.arguments.argString("file") ?: return McpResult("`file` is required.", isError = true)
        val line = call.arguments.argInt("line") ?: return McpResult("`line` is required.", isError = true)
        if (line < 1) return McpResult("`line` is 1-based.", isError = true)
        val base = project.basePath ?: System.getProperty("user.home")
        val io = if (File(path).isAbsolute) File(path) else File(base, path)
        val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(io)
            ?: return McpResult("File not found: $path", isError = true)
        val line0 = line - 1
        val remove = call.arguments.argBool("remove") == true
        val condition = call.arguments.argString("condition")
        val logExpression = call.arguments.argString("log")
        if (!remove && call.arguments.argBool("force") != true) {
            for (expression in listOfNotNull(condition, logExpression)) {
                val (unknown, nearby) = undeclared(file, line0, expression) ?: continue
                return McpResult(
                    "Not set: ${unknown.joinToString { "`$it`" }} in `$expression` is not declared in $path (as a variable, parameter, field or " +
                        "function). The debugger could not evaluate it, and the IDE would show the user a blocking error dialog " +
                        "every time the line is reached." +
                        (if (nearby.isEmpty()) "" else " Names declared near line $line: ${nearby.joinToString()}.") +
                        " Use a name that exists there (a member of another object is `obj.name`, not `name`), or set the breakpoint " +
                        "without a condition and use debug_eval once it stops. Pass force=true to set it anyway.",
                    isError = true,
                )
            }
        }

        return onEdt {
            WriteAction.compute<McpResult, RuntimeException> {
                val manager = XDebuggerManager.getInstance(project).breakpointManager
                val here = lineBreakpoints().filter { it.fileUrl == file.url && it.line == line0 }
                if (remove) {
                    here.forEach { manager.removeBreakpoint(it) }
                    return@compute McpResult(if (here.isEmpty()) "There was no breakpoint at $path:$line." else "Removed ${here.size} breakpoint(s) at $path:$line.")
                }
                val util = XDebuggerUtil.getInstance()
                val bp = here.firstOrNull() ?: run {
                    val type = util.lineBreakpointTypes.filter { it.canPutAt(file, line0, project) }.maxByOrNull { it.priority }
                        ?: return@compute McpResult(
                            "Can't put a breakpoint at $path:$line: no debugger for this file type, or the line has no code.",
                            isError = true,
                        )
                    addLine(manager, type, file, line0)
                }
                bp.isEnabled = true
                if (condition != null) bp.conditionExpression = util.createExpression(condition, null, null, EvaluationMode.EXPRESSION)
                if (logExpression != null) {
                    bp.logExpressionObject = util.createExpression(logExpression, null, null, EvaluationMode.EXPRESSION)
                    bp.suspendPolicy = SuspendPolicy.NONE
                }
                kiroBreakpoints.add(bp)
                McpResult((if (here.isEmpty()) "Added " else "Updated ") + describe(bp) + ".")
            }
        }
    }

    /**
     * The names in [expression] that the file declares nowhere (so a condition using them can't be evaluated), with names
     * declared shortly above the line for the message; null when nothing is wrong or the file can't be checked.
     */
    private fun undeclared(file: VirtualFile, line0: Int, expression: String): Pair<List<String>, List<String>>? = readNow {
        val ids = ConditionNames.identifiers(expression)
        if (ids.isEmpty()) return@readNow null
        val psi: PsiFile = PsiManager.getInstance(project).findFile(file) ?: return@readNow null
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return@readNow null
        if (line0 !in 0 until doc.lineCount || doc.textLength > MAX_CHECKED_CHARS) return@readNow null
        val declared = HashMap<String, Int>()
        PsiTreeUtil.processElements(psi, PsiElementProcessor { e ->
            if (e is PsiNamedElement && e !is PsiFile) e.name?.let { declared.putIfAbsent(it, doc.getLineNumber(e.textOffset.coerceIn(0, doc.textLength))) }
            true
        })
        if (declared.isEmpty()) return@readNow null // no language support to check against
        val unknown = ids.filter { it !in declared }
        if (unknown.isEmpty()) return@readNow null
        val nearby = declared.entries.filter { it.value in (line0 - 25)..line0 && it.key.firstOrNull()?.isLowerCase() == true }
            .sortedByDescending { it.value }.map { it.key }.distinct().take(12)
        unknown to nearby
    }

    @Suppress("UNCHECKED_CAST")
    private fun addLine(manager: XBreakpointManager, type: XLineBreakpointType<*>, file: VirtualFile, line: Int): XLineBreakpoint<*> {
        val t = type as XLineBreakpointType<XBreakpointProperties<*>>
        return manager.addLineBreakpoint(t, file.url, line, t.createBreakpointProperties(file, line))
    }

    private fun lineBreakpoints(): List<XLineBreakpoint<*>> = readNow {
        XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints.filterIsInstance<XLineBreakpoint<*>>()
    }

    private fun describe(bp: XLineBreakpoint<*>): String = readNow {
        val file = bp.sourcePosition?.file?.let(::relative) ?: bp.presentableFilePath
        val parts = mutableListOf<String>()
        if (!bp.isEnabled) parts.add("disabled")
        bp.conditionExpression?.expression?.takeIf { it.isNotBlank() }?.let { parts.add("condition: $it") }
        bp.logExpressionObject?.expression?.takeIf { it.isNotBlank() }?.let { parts.add("logs: $it") }
        if (bp.suspendPolicy == SuspendPolicy.NONE) parts.add("does not stop")
        if (bp in kiroBreakpoints) parts.add("set by Kiro")
        "breakpoint at $file:${bp.line + 1}" + if (parts.isEmpty()) "" else " (${parts.joinToString(", ")})"
    }

    private fun <T> onEdt(block: () -> T): T {
        val result = CompletableFuture<T>()
        ApplicationManager.getApplication().invokeLater({
            try {
                result.complete(block())
            } catch (e: Throwable) {
                result.completeExceptionally(e)
            }
        }, ModalityState.defaultModalityState())
        return try {
            result.get(EDT_TIMEOUT_S, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private companion object {
        const val MAX_FRAMES = 12
        const val MAX_CHECKED_CHARS = 400_000
        /** Console lines shown with a pause: what the program printed just before it stopped. */
        const val CONSOLE_LINES = 10
        const val MAX_VARIABLES = 40
        const val MAX_CHILDREN = 25
        const val FRAMES_TIMEOUT_MS = 3_000L
        const val VARIABLES_TIMEOUT_MS = 4_000L
        const val EVAL_TIMEOUT_MS = 10_000L
        const val EDT_TIMEOUT_S = 20L
        val ACTIONS = listOf("step_over", "step_into", "step_out", "resume", "pause", "stop", "run_to_line", "wait")
        const val PAUSED_FOOTER = "The program stays paused until you act. Use debug_state and debug_eval to look " +
            "around, and debug_control to step, resume or stop. If you need the user to do something first, ask them."
    }
}
