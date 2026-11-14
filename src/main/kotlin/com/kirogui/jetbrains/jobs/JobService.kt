package com.kirogui.jetbrains.jobs

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.kirogui.jetbrains.mcp.McpCall
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpServer
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.ToolCallLog
import com.kirogui.jetbrains.settings.KiroSettings
import com.kirogui.jetbrains.mcp.argBool
import com.kirogui.jetbrains.mcp.argInt
import com.kirogui.jetbrains.mcp.argString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Event-driven work for Kiro. Kiro starts a job through an MCP tool the IDE serves (one URL per chat), the tool
 * returns at once and the chat goes on. When the job needs attention (it ended, the debugger paused) a [Notice] is
 * queued for its chat, and the chat panel sends it as a prompt as soon as that chat is idle.
 *
 * Kinds of job plug in by contributing tools: [ShellJobs] (`bg_run`) and [DebugJobs] (`debug_*`).
 */
@Service(Service.Level.PROJECT)
class JobService(private val project: Project) : Disposable {

    /** What changed: a job's state, or a new [Notice] (then non-null). Called on any thread. */
    fun interface Listener {
        fun changed(notice: Notice?)
    }

    /** Where a chat's MCP server lives; [token] is bound to the chat's session id. */
    class Endpoint(val token: String, val url: String)

    private val log = Logger.getInstance(JobService::class.java)
    private val jobs = CopyOnWriteArrayList<Job>()
    private val nextId = AtomicInteger(1)
    private val listeners = CopyOnWriteArrayList<Listener>()
    private val tokens = ConcurrentHashMap<String, String>() // session id -> token
    private val wake = WakeQueue()
    private var server: McpServer? = null

    /** What Kiro asked the IDE tools to do, newest last 300 calls. */
    val callLog = ToolCallLog()

    /** How far each chat has read each log (job output, run consoles). */
    val reader = LogReader()

    private val shell = ShellJobs(project, this)
    val runLogs = RunLogs(project, this, reader, { handler -> jobs.firstOrNull { it.process === handler } }, ::find)
    private val debug: DebugJobs? = try {
        DebugJobs(project, this)
    } catch (e: LinkageError) {
        log.info("No debugger in this IDE; debug tools are off", e)
        null
    }

    fun addListener(parent: Disposable, listener: Listener) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    /**
     * The MCP endpoint to hand to `session/new` or `session/load`. A reopened chat keeps its token. Null if the
     * server can't start; the chat then simply has no IDE tools.
     */
    @Synchronized
    fun endpoint(sessionId: String?): Endpoint? {
        val s = server ?: try {
            McpServer(SERVER_NAME, VERSION, INSTRUCTIONS, callLog::record) { tools() }.also {
                server = it
                log.info("IDE tools for Kiro on 127.0.0.1:${it.port}")
            }
        } catch (e: Exception) {
            log.warn("Could not start the IDE tool server", e)
            return null
        }
        val token = sessionId?.let { tokens[it] } ?: s.newToken(sessionId)
        sessionId?.let { tokens[it] = token }
        return Endpoint(token, s.url(token))
    }

    /** Called once `session/new` has said which id the chat got. */
    fun bind(endpoint: Endpoint, sessionId: String) {
        server?.bind(endpoint.token, sessionId)
        tokens[sessionId] = endpoint.token
    }

    fun newJob(kind: JobKind, title: String, detail: String, sessionId: String?): Job =
        Job(nextId.getAndIncrement(), kind, title, detail, sessionId).also { jobs.add(it) }

    fun all(): List<Job> = jobs.toList()

    fun find(id: Int): Job? = jobs.firstOrNull { it.id == id }

    fun activeCount(sessionId: String?): Int = jobs.count { it.state.active && it.sessionId == sessionId }

    /** Takes ended jobs off the list (the Jobs tab's "Clear"). News of them still waiting for Kiro is kept. */
    fun clearEnded() {
        jobs.removeIf { !it.state.active }
        changed()
    }

    /** The user pressed Stop on [job] in the IDE; Kiro is told it was stopped by the user. */
    fun stopByUser(job: Job): Boolean {
        if (!job.state.active) return false
        val stop = job.stopper ?: return false
        job.stoppedByUser.set(true)
        stop()
        return true
    }

    fun changed() = listeners.forEach { it.changed(null) }

    /** Queues [notice] for its chat and tells the listeners. */
    fun post(notice: Notice) {
        wake.post(notice)
        listeners.forEach { it.changed(notice) }
    }

    /**
     * The standard "job ended" notice. It is dropped if Kiro has moved on by the time the chat is idle: a newer job
     * runs the same command or configuration, or (for a debug session that simply finished) Kiro started anything
     * newer. Such a result only interrupts; the Jobs tab still shows it.
     */
    fun ended(job: Job) {
        val sid = job.sessionId ?: return
        post(Notice(sid, "job-${job.id}", JobText.endedSummary(job), JobText.ended(job), obsolete = { superseded(job) }))
    }

    fun superseded(job: Job): Boolean = jobs.any { newer ->
        newer.id > job.id && newer.sessionId == job.sessionId &&
            (newer.detail == job.detail || (job.kind == JobKind.DEBUG && job.state == JobState.FINISHED))
    }

    /** The session is one the plugin runs for itself (a debug_task helper); nobody sends it wake-up messages. */
    fun isHelper(sessionId: String?): Boolean =
        sessionId != null && project.getService(com.kirogui.jetbrains.service.KiroAcpService::class.java).isHelper(sessionId)

    fun hasNotices(sessionId: String): Boolean = wake.has(sessionId)

    fun takeNotices(sessionId: String): List<Notice> = wake.take(sessionId)

    fun dropNotice(sessionId: String, key: String) = wake.drop(sessionId, key)

    /** A helper session ended: its still-running jobs are stopped and news meant for it is forgotten. */
    fun endSession(sessionId: String) {
        for (job in jobs) if (job.sessionId == sessionId && job.state.active) {
            job.stoppedByKiro.set(true)
            try {
                job.stopper?.invoke()
            } catch (_: Exception) {
            }
        }
        wake.dropAll(sessionId)
        tokens.remove(sessionId)
    }

    private val code = CodeTools(project, this)

    private val helper = HelperJobs(project, this)

    private val tests: TestJobs? = try {
        TestJobs(project, this)
    } catch (e: LinkageError) {
        log.info("No test runner in this IDE; test_run is off", e)
        null
    }

    private fun tools(): List<McpTool> {
        val off = ToolFilter.parse(KiroSettings.getInstance().state.disabledTools)
        return allTools().filter { it.name !in off }
    }

    /** Every tool this IDE can offer, for the settings hint and the filter. */
    fun allTools(): List<McpTool> =
        shell.tools() + common() + (tests?.tools() ?: emptyList()) + (debug?.tools() ?: emptyList()) + (if (debug != null) helper.tools() else emptyList()) + runLogs.tools() + code.tools()

    /** Names of the tools Kiro gets right now (none when the IDE tools are off in the settings). */
    fun enabledToolNames(): Set<String> {
        val state = KiroSettings.getInstance().state
        if (!state.ideTools) return emptySet()
        val off = ToolFilter.parse(state.disabledTools)
        return allTools().map { it.name }.filter { it !in off }.toSet()
    }

    /** The tool list as text, with the ones the settings turn off marked: what Kiro can use right now. */
    fun toolList(): String {
        val off = ToolFilter.parse(KiroSettings.getInstance().state.disabledTools)
        return allTools().joinToString("\n\n") {
            "${if (it.name in off) "[off] " else ""}${it.name}\n    ${it.description.take(300)}"
        }
    }

    private fun common(): List<McpTool> = listOf(
        McpTool(
            name = "job_status",
            description = "Show background and debug jobs started from this chat. With `id`: that job's state and the " +
                "last lines of its output. Only use it when the user asks or you need the output early; you are told " +
                "automatically when a job ends. $READ_HINT",
            inputSchema = Schema.obj(
                emptyList(),
                "id" to Schema.integer("Job id. Leave out to list the jobs."),
                "lines" to Schema.integer("How many output lines to show (default 40, at most 400)."),
                "new_only" to Schema.boolean("Only output printed since you last read this job."),
                "from_line" to Schema.integer("Read forward from this line number."),
                "contains" to Schema.string("Only lines containing this text (any case)."),
            ),
            call = ::status,
        ),
        McpTool(
            name = "job_stop",
            description = "Stop a running background or debug job.",
            inputSchema = Schema.obj(listOf("id"), "id" to Schema.integer("Job id.")),
            call = ::stop,
        ),
    )

    private fun status(call: McpCall): McpResult {
        val id = call.arguments.argInt("id")
        if (id == null) {
            val mine = jobs.filter { call.sessionId == null || it.sessionId == call.sessionId }
            if (mine.isEmpty()) return McpResult("No jobs have been started from this chat.")
            return McpResult(mine.joinToString("\n") { JobText.line(it) })
        }
        val job = find(id) ?: return McpResult("There is no job #$id.", isError = true)
        val lines = (call.arguments.argInt("lines") ?: 40).coerceIn(1, 400)
        val fromLine = call.arguments.argInt("from_line")?.toLong()
        val newOnly = call.arguments.argBool("new_only") ?: false
        val contains = call.arguments.argString("contains")
        val readsOutput = fromLine != null || newOnly || contains != null
        // A finished test run: its report, unless the output itself is asked for.
        job.report?.takeIf { !readsOutput }?.let { return McpResult(it) }
        val output = reader.read(call.sessionId, "job-$id", "output", job.output.numbered(), lines, fromLine, newOnly, contains)
        val progress = job.progress?.takeIf { job.state.active }?.let { runCatching { it() }.getOrNull() }
        return McpResult(JobText.header(job) + (progress?.let { "\n$it" } ?: "") + "\n" + output)
    }

    private fun stop(call: McpCall): McpResult {
        val id = call.arguments.argInt("id") ?: return McpResult("`id` is required.", isError = true)
        val job = find(id) ?: return McpResult("There is no job #$id.", isError = true)
        if (!job.state.active) return McpResult("Job #$id is not running (${JobText.outcome(job)}).")
        job.stoppedByKiro.set(true)
        job.sessionId?.let { wake.drop(it, "job-$id") }
        job.stopper?.invoke() ?: return McpResult("Job #$id can't be stopped yet; try again in a moment.", isError = true)
        return McpResult("Stopping job #$id.")
    }

    override fun dispose() {
        for (job in jobs) if (job.state.active) try {
            job.stoppedByKiro.set(true)
            job.stopper?.invoke()
        } catch (_: Exception) {
        }
        server?.close()
        server = null
    }

    companion object {
        /** How to read a log without reading the same lines twice; shared by job_status and run_logs. */
        const val READ_HINT = "Lines are numbered from the start and each result says which ones it shows. To get only " +
            "what was printed since you last read it, pass new_only: true instead of reading the same lines again; " +
            "from_line reads forward from a given line."
        const val SERVER_NAME = "kirogui"
        const val VERSION = "0.1.3"
        const val INSTRUCTIONS = "Tools served by the JetBrains IDE that hosts this chat. They let you run slow work " +
            "without blocking the conversation: bg_run starts a shell command, test_run runs tests with the IDE's test " +
            "runner (all of the project's by default), debug_start runs a program under the IDE debugger. They return " +
            "at once; the IDE sends you a message when the job ends (for tests: a report of the failures) or the " +
            "debugger stops, so never poll, sleep or wait for them. run_logs reads the console of any run in the IDE. " +
            "For understanding code prefer the IDE's index over grep and reading whole files: file_structure (outline of a file), " +
            "find_definition, find_references, find_implementations and call_graph follow the real code, find_symbol and find_file " +
            "look names up, project_modules and list_dir show the layout, editor_state shows what the user has open. " +
            "Prefer test_run over running a test command in a shell, and debug_start over adding print statements."
    }
}
