package com.kirogui.jetbrains.service

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.kirogui.jetbrains.acp.AcpClient
import com.kirogui.jetbrains.acp.AcpEvent
import com.kirogui.jetbrains.acp.JsonRpcException
import com.kirogui.jetbrains.acp.Memory
import com.kirogui.jetbrains.acp.MemoryMode
import com.kirogui.jetbrains.acp.MemoryParse
import com.kirogui.jetbrains.acp.httpMcpServer
import com.kirogui.jetbrains.acp.PermissionHandler
import com.kirogui.jetbrains.acp.PlanUsage
import com.kirogui.jetbrains.acp.PromptImage
import com.kirogui.jetbrains.acp.SessionInfo
import com.kirogui.jetbrains.jobs.JobService
import com.kirogui.jetbrains.kiro.EditPreview
import com.kirogui.jetbrains.settings.KiroLaunch
import com.kirogui.jetbrains.settings.KiroSettings
import com.kirogui.jetbrains.ui.DiffReviewDialog
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Talks to one long-lived `kiro-cli acp` process per project. Chats are [Conversation]s on that same process: a new
 * chat or a reopened session takes about a second instead of the two to three seconds of a new process, and several
 * chats can be open and busy at once (kiro-cli runs sessions concurrently). Every session on a process keeps its own
 * MCP servers alive and ACP has no way to close one, so when no chat is live the process is recycled after a handful
 * of sessions.
 */
@Service(Service.Level.PROJECT)
class KiroAcpService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(KiroAcpService::class.java)

    // Read from the EDT (e.g. to highlight the open session), so never guarded by the lock that open/close hold.
    @Volatile private var process: Process? = null
    @Volatile private var client: AcpClient? = null
    private var sessionsInProcess = 0

    private val conversations = CopyOnWriteArrayList<Conversation>()

    /** The conversation whose `session/new` or `session/load` is in flight: events of a session not yet known go to it. */
    @Volatile private var opening: Conversation? = null

    /** Sessions a chat has moved away from; their late events must not land in the chat's new transcript. */
    private val stale = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** `/trust on` or `--trust-all-tools` on the v3 engine: permission requests are approved without asking, until the IDE closes. */
    @Volatile var trustAll = false

    private fun trusting() = trustAll || KiroSettings.getInstance().state.trustAllTools

    /** Said once in the chat that started kiro-cli: what became of v2-only flags in the arguments. */
    @Volatile private var launchNotes: List<String> = emptyList()

    // Remembered across "New chat" so the user's agent/model choice sticks.
    private var preferredModel: String? = null
    private var preferredMode: String? = null

    /** A chat's line to Kiro. Disposing [parent] ends it (a running turn is cancelled). */
    fun conversation(parent: Disposable): Conversation {
        val conv = Conversation()
        conversations.add(conv)
        Disposer.register(parent) { release(conv) }
        return conv
    }

    /** One chat. Methods run on a background thread and return futures; events arrive on background threads. */
    inner class Conversation internal constructor() {
        @Volatile internal var info: SessionInfo? = null
        @Volatile internal var expecting: String? = null
        private val listeners = CopyOnWriteArrayList<(AcpEvent) -> Unit>()

        /** Events arrive on background threads; the listener must hop to the EDT itself. */
        fun addListener(listener: (AcpEvent) -> Unit) {
            listeners.add(listener)
        }

        internal fun deliver(event: AcpEvent) = listeners.forEach { it(event) }

        /** The live session if there is one, else starts the process and a first session (background thread). */
        fun start(): CompletableFuture<SessionInfo> = async { ensureLive(this) }

        /** A fresh session on the running process. */
        fun newSession(): CompletableFuture<SessionInfo> = async { openSession(this, null, null) }

        /** Reloads a stored session on the running process; its history is replayed through the event listeners. */
        fun resume(sessionId: String, cwd: String): CompletableFuture<SessionInfo> = async { openSession(this, sessionId, cwd) }

        /** The future completes with the stop reason, or fails with the cause. */
        fun prompt(text: String, images: List<PromptImage> = emptyList()): CompletableFuture<String> = async {
            val (c, session) = live(this)
            try {
                c.prompt(session.sessionId, text, images).get()
            } finally {
                FileSync.syncAll() // whatever Kiro changed on disk during the turn shows up in the editor
            }
        }

        /** Summarises the conversation to free context. The chat is told about it by an [AcpEvent.Compacted] event. */
        fun compact(): CompletableFuture<Unit> = async {
            val (c, session) = live(this)
            c.compact(session.sessionId)
        }

        fun setModel(modelId: String): CompletableFuture<Unit> = async {
            val (c, session) = live(this)
            c.setModel(session.sessionId, modelId, session.modelViaConfig)
            synchronized(this@KiroAcpService) {
                preferredModel = modelId
                info = info?.copy(currentModelId = modelId)
            }
        }

        /** Sets a config option of the session (v3 engine: `autopilot`, ...). */
        fun setConfig(id: String, value: String): CompletableFuture<Unit> = async {
            val (c, session) = live(this)
            c.setConfigOption(session.sessionId, id, value)
        }

        fun setEffort(value: String): CompletableFuture<Unit> = async {
            val (c, session) = live(this)
            val id = session.effortConfigId ?: return@async
            c.setConfigOption(session.sessionId, id, value)
            synchronized(this@KiroAcpService) { info = info?.copy(currentEffortId = value) }
        }

        fun setMode(modeId: String): CompletableFuture<Unit> = async {
            val (c, session) = live(this)
            c.setMode(session.sessionId, modeId)
            synchronized(this@KiroAcpService) {
                preferredMode = modeId
                info = info?.copy(currentModeId = modeId)
            }
        }

        /** Account-wide plan credits; null if Kiro can't say (not signed in, no cap info). */
        fun usage(): CompletableFuture<PlanUsage?> = async {
            val (c, session) = live(this)
            try {
                c.usage(session.sessionId)
            } catch (e: Exception) {
                // The v3 engine can't answer /usage ("no persistence classification"); the v2 engine can.
                usageViaV2() ?: throw e
            }
        }

        /** The id of the live session, or null before one is started. Cheap and lock-free. */
        fun currentSessionId(): String? = info?.sessionId

        /** The agent the live session runs as (ACP calls agents "modes"), or null before a session exists. */
        fun currentAgentId(): String? = info?.currentModeId

        /** The first chat of the project: the one that shows balloons for jobs no open chat owns. */
        val isPrimary: Boolean get() = conversations.firstOrNull() === this

        /** Some chat (this one included) has [sessionId] open. */
        fun isOpenAnywhere(sessionId: String): Boolean = conversations.any { it.info?.sessionId == sessionId }

        fun cancel() {
            val c = client
            val s = info
            if (c != null && s != null) c.cancel(s.sessionId)
        }

        /** Restarts the process (every chat's session ends; the others see "closed" and offer to reconnect). */
        fun restart(): CompletableFuture<Unit> = async { restartProcess(this) }
    }

    private fun release(conv: Conversation) {
        conversations.remove(conv)
        val sid = conv.info?.sessionId ?: return
        conv.info = null
        stale.add(sid)
        client?.cancel(sid)
    }

    /** Is kiro-cli running? Memories can only be read from a running process; the Library does not start one for them. */
    fun isRunning(): Boolean = client != null

    /**
     * What Kiro remembers, or null when kiro-cli is not running or has no memory (the v2 engine). Blocks: call it from a
     * background thread.
     */
    fun memories(): List<Memory>? {
        val c = client ?: return null
        return try {
            c.memories()
        } catch (e: Exception) {
            log.info("Could not read Kiro's memories: ${(e as? java.util.concurrent.ExecutionException)?.cause?.message ?: e.message}")
            null
        }
    }

    /** One memory in full (blocks; background thread). */
    fun memory(id: String): Memory? = client?.memory(id)

    /** Deletes a memory (blocks; background thread). Throws if Kiro refuses. */
    fun deleteMemory(id: String) {
        val c = client ?: throw IllegalStateException("kiro-cli is not running.")
        try {
            c.deleteMemory(id)
        } catch (e: java.util.concurrent.ExecutionException) {
            throw IllegalStateException(e.cause?.message ?: e.message, e)
        }
    }

    /** Stops the process; the next call starts a fresh one. */
    @Synchronized
    fun reset() = shutdown()

    @Synchronized
    private fun restartProcess(by: Conversation) {
        shutdown()
        // The process's own "closed" event is ignored once cleared, so the other chats are told here.
        conversations.filter { it !== by }.forEach { it.deliver(AcpEvent.Closed("kiro-cli was restarted from another chat")) }
    }

    private fun <T> async(block: () -> T): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                result.complete(block())
            } catch (e: Throwable) {
                log.warn("Kiro ACP call failed", e)
                result.completeExceptionally(e)
            }
        }
        return result
    }

    private fun live(conv: Conversation): Pair<AcpClient, SessionInfo> {
        val c = client
        val s = conv.info
        if (c != null && s != null) return c to s
        ensureLive(conv)
        return client!! to conv.info!!
    }

    /** Idempotent under the lock, so a "start" racing a first prompt can't open two sessions. */
    @Synchronized
    private fun ensureLive(conv: Conversation): SessionInfo = conv.info ?: openSession(conv, null, null)

    @Synchronized
    private fun openSession(conv: Conversation, resumeId: String?, cwdOverride: String?): SessionInfo {
        val cwd = cwdOverride ?: project.basePath ?: System.getProperty("user.home")
        var c = client
        // Recycling the process would end the other chats' sessions, so only when none is live.
        val recycle = sessionsInProcess >= MAX_SESSIONS_PER_PROCESS && workers.isEmpty() &&
            conversations.none { it !== conv && it.info != null }
        if (c == null || recycle) {
            shutdown()
            c = launch(cwd)
        }
        val previous = conv.info?.sessionId
        // From now on the chat's old session is not wanted any more, and the target's events are (a new one's id isn't known yet).
        previous?.let { stale.add(it) }
        resumeId?.let { stale.remove(it) }
        conv.expecting = resumeId
        opening = conv
        c.startTimeoutSeconds = startTimeout()
        val began = System.nanoTime()
        log.info(if (resumeId != null) "Loading session $resumeId" else "Opening a new session")
        val ideTools = ideToolsFor(c, resumeId)
        if (ideTools != null) keepGuidance()
        var session = try {
            open(c, resumeId, cwd, ideTools)
        } catch (e: Exception) {
            previous?.let { stale.remove(it) }
            conv.expecting = null
            opening = null
            log.warn("Opening the session failed after ${(System.nanoTime() - began) / 1_000_000} ms", e)
            val text = e.cause?.message ?: e.message.orEmpty()
            if (AUTH_HINT.containsMatchIn(text)) {
                throw IllegalStateException("$text\n\nSign in with `kiro-cli login` in a terminal, then press Retry.", e)
            }
            throw e
        }
        ideTools?.let { project.getService(JobService::class.java).bind(it, session.sessionId) }
        log.info("Session ${session.sessionId} ready in ${(System.nanoTime() - began) / 1_000_000} ms")
        // A reloaded session keeps its own agent and model; only fresh sessions get the remembered choice.
        if (resumeId == null) {
            preferredMode?.takeIf { it != session.currentModeId && session.modes.any { m -> m.id == it } }?.let {
                c.setMode(session.sessionId, it)
                session = session.copy(currentModeId = it)
            }
            preferredModel?.takeIf { it != session.currentModelId && session.models.any { m -> m.id == it } }?.let {
                c.setModel(session.sessionId, it, session.modelViaConfig)
                session = session.copy(currentModelId = it)
            }
        }
        conv.info = session
        conv.expecting = null
        opening = null
        sessionsInProcess++
        launchNotes.also { launchNotes = emptyList() }.forEach { conv.deliver(AcpEvent.DisplayError(it)) }
        return session
    }

    /** Sessions whose remembered model (or `--model`) has been applied once the model list arrived. */
    private val modelApplied = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * The v3 engine lists its models after `session/new` answered. They are kept with the chat's session info, and the
     * model the user chose earlier is applied now (off the reader thread: it waits for an answer the reader delivers).
     */
    private fun applyConfigOptions(sid: String?, event: AcpEvent.ConfigOptions) {
        val conv = conversations.firstOrNull { it.info?.sessionId == sid || it.expecting == sid } ?: return
        val info = conv.info
        if (info != null) {
            conv.info = info.copy(
                models = event.models.ifEmpty { info.models },
                currentModelId = event.currentModelId ?: info.currentModelId,
                modelViaConfig = event.models.isNotEmpty() || info.modelViaConfig,
                efforts = event.efforts.ifEmpty { info.efforts },
                currentEffortId = event.currentEffortId ?: info.currentEffortId,
                effortConfigId = event.effortConfigId ?: info.effortConfigId,
            )
        }
        val wanted = preferredModel ?: return
        val session = sid ?: return
        if (wanted == event.currentModelId || event.models.none { it.id == wanted } || !modelApplied.add(session)) return
        val c = client ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                c.setModel(session, wanted, true)
            } catch (e: Exception) {
                log.info("Could not apply the model $wanted", e)
            }
        }
    }

    /** Hands an event of the process to the chat it belongs to. */
    private fun route(sid: String?, event: AcpEvent) {
        syncFiles(event)
        if (event is AcpEvent.Closed) {
            // The process died by itself: every chat loses its session.
            process = null
            client = null
            sessionsInProcess = 0
            opening = null
            conversations.forEach {
                it.info = null
                it.expecting = null
            }
            conversations.forEach { it.deliver(event) }
            return
        }
        if (sid != null && workers[sid]?.accept(event) == true) return
        if (event is AcpEvent.ConfigOptions) applyConfigOptions(sid, event)
        val target = when {
            sid == null -> opening
            else -> conversations.firstOrNull { it.info?.sessionId == sid || it.expecting == sid }
                ?: if (sid in stale) null else opening?.takeIf { it.expecting == null }
        }
        target?.deliver(event)
    }

    /** Keeps `~/.kiro/steering/kirogui-ide-tools.md` current, so Kiro's guidance on the IDE tools survives a compaction. */
    private fun keepGuidance() {
        if (!KiroSettings.getInstance().state.autoGuidance) return
        try {
            val resources = com.kirogui.jetbrains.kiro.KiroResources(project.basePath?.let { Path.of(it) }, Path.of(System.getProperty("user.home")))
            if (resources.ensureToolGuidance(com.kirogui.jetbrains.kiro.Scope.GLOBAL)) log.info("Wrote the IDE tool guidance to ~/.kiro/steering")
            // A copy in the project (it overrides the global one) is always refreshed; created only when the setting asks for it.
            if (resources.ensureToolGuidance(com.kirogui.jetbrains.kiro.Scope.WORKSPACE, createIfMissing = KiroSettings.getInstance().state.guidanceInProject)) log.info("Refreshed the IDE tool guidance in .kiro/steering")
        } catch (e: Exception) {
            log.info("Could not write the IDE tool guidance", e)
        }
    }

    // ---------- helper sessions ----------

    /** What a helper session has said so far; events of its session end up here instead of in a chat. */
    private class Worker {
        val text = StringBuilder()
        @Volatile var credits = 0.0
        @Volatile var model: String? = null

        /** Told about each step the helper takes (tool call titles), so the job can show them while it works. */
        @Volatile var progress: ((String) -> Unit)? = null

        /** True when the event was consumed (every event of a helper session is). */
        fun accept(event: AcpEvent): Boolean {
            when (event) {
                is AcpEvent.AgentText -> synchronized(text) { text.append(event.text) }
                is AcpEvent.Metadata -> event.credits?.let { credits += it }
                is AcpEvent.ToolCall -> progress?.invoke("• " + event.title)
                else -> Unit
            }
            return true
        }

        fun reply(): String = synchronized(text) { text.toString().trim() }
    }

    private val workers = java.util.concurrent.ConcurrentHashMap<String, Worker>()

    /** Result of [runHelper]. */
    class HelperResult(val text: String, val stopReason: String, val credits: Double, val model: String?)

    /** Is [sessionId] a helper session started by [runHelper]? (Its tool calls are answered without asking the user.) */
    fun isHelper(sessionId: String?): Boolean = sessionId != null && workers.containsKey(sessionId)

    /**
     * Runs [task] in a separate Kiro session with only the IDE tools and a cheaper model, and returns what it wrote.
     * Blocks until the helper's turn ends (or [timeoutSeconds] passes, when it is cancelled); call it from a
     * background thread. [onStarted] gets a function that cancels the helper.
     */
    fun runHelper(task: String, timeoutSeconds: Long, progress: (String) -> Unit = {}, onSession: (String) -> Unit = {}, onStarted: (cancel: () -> Unit) -> Unit): HelperResult {
        val c = client ?: throw IllegalStateException("kiro-cli is not running.")
        val jobs = project.getService(JobService::class.java)
        val endpoint = jobs.endpoint(null) ?: throw IllegalStateException("The IDE tool server is not available.")
        val cwd = project.basePath ?: System.getProperty("user.home")
        val worker = Worker()
        val info = synchronized(this) {
            val created = c.newSession(cwd, JsonArray(listOf(httpMcpServer(JobService.SERVER_NAME, endpoint.url))))
            workers[created.sessionId] = worker
            sessionsInProcess++
            created
        }
        val sid = info.sessionId
        jobs.bind(endpoint, sid)
        worker.progress = progress
        onSession(sid)
        progress("helper session $sid (it is also in the Sessions list while and after it runs)")
        try {
            pickHelperModel(info)?.let {
                try {
                    c.setModel(sid, it, info.modelViaConfig)
                    worker.model = it
                } catch (e: Exception) {
                    log.info("Could not switch the helper session to $it", e)
                }
            }
            val turn = c.prompt(sid, task)
            onStarted { c.cancel(sid) }
            val reason = try {
                turn.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: java.util.concurrent.TimeoutException) {
                c.cancel(sid)
                runCatching { turn.get(10, java.util.concurrent.TimeUnit.SECONDS) }
                "timeout"
            }
            return HelperResult(worker.reply(), reason, worker.credits, worker.model ?: info.currentModelId)
        } finally {
            stale.add(sid)
            workers.remove(sid)
            jobs.endSession(sid)
            runCatching { c.closeSession(sid) }
        }
    }

    /** The model for helper sessions: the setting, else a "haiku" one, else whatever the session started with. */
    private fun pickHelperModel(info: SessionInfo): String? {
        val wanted = KiroSettings.getInstance().state.workerModel.trim()
        if (wanted.isNotEmpty()) {
            return info.models.firstOrNull { it.id.equals(wanted, true) || it.name.equals(wanted, true) }?.id
                ?: info.models.firstOrNull { it.id.contains(wanted, true) }?.id
        }
        return info.models.firstOrNull { it.id.contains("haiku", true) || it.name.contains("haiku", true) }?.id
    }

    /** The IDE's own MCP server (background commands, debugger) for this chat, if Kiro can take one and it's on. */
    private fun ideToolsFor(c: AcpClient, sessionId: String?): JobService.Endpoint? {
        if (!KiroSettings.getInstance().state.ideTools || !c.supportsHttpMcp) return null
        return project.getService(JobService::class.java).endpoint(sessionId)
    }

    /**
     * Opens or reloads the session with the IDE tools. Should Kiro reject the extra MCP server, the chat opens
     * without it rather than not at all.
     */
    private fun open(c: AcpClient, resumeId: String?, cwd: String, ideTools: JobService.Endpoint?): SessionInfo {
        val servers = ideTools?.let { JsonArray(listOf(httpMcpServer(JobService.SERVER_NAME, it.url))) } ?: JsonArray(emptyList())
        val settings = KiroSettings.getInstance().state
        val meta = MemoryParse.sessionMeta(MemoryMode.of(settings.memoryMode), settings.memoryReflection)
        fun attempt(list: JsonArray) = if (resumeId != null) c.loadSession(resumeId, cwd, list, meta) else c.newSession(cwd, list, meta)
        return try {
            attempt(servers)
        } catch (e: java.util.concurrent.ExecutionException) {
            if (servers.isEmpty() || e.cause !is JsonRpcException) throw e
            log.warn("kiro-cli refused the IDE tool server; opening the session without it", e)
            attempt(JsonArray(emptyList()))
        }
    }

    private fun startTimeout(): Long = KiroSettings.getInstance().state.startTimeoutSeconds
        .coerceIn(KiroSettings.MIN_START_TIMEOUT, KiroSettings.MAX_START_TIMEOUT).toLong()

    private fun launch(cwd: String): AcpClient {
        val settings = KiroSettings.getInstance().state
        launchNotes = carryOutV2Flags(settings.kiroArgs, settings.agentEngine)
        val cmd = GeneralCommandLine(
            KiroLaunch.command(settings.kiroPath, KiroLaunch.withEngine(KiroLaunch.compatibleArgs(settings.kiroArgs, settings.agentEngine), settings.agentEngine), settings.kiroShell),
        )
            .withWorkDirectory(cwd)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(KiroLaunch.environment(settings.kiroEnv))
        log.info("Starting kiro-cli: ${cmd.commandLineString} (cwd $cwd)")
        val began = System.nanoTime()
        val proc = cmd.createProcess()
        log.info("kiro-cli started, pid ${proc.pid()}")
        val stderrTail = java.util.concurrent.ConcurrentLinkedDeque<String>()
        Thread({
            proc.errorStream.bufferedReader().forEachLine {
                log.info("kiro-cli: $it")
                stderrTail.addLast(it)
                while (stderrTail.size > 8) stderrTail.pollFirst()
            }
        }, "kiro-cli-stderr").apply { isDaemon = true }.start()

        val c = AcpClient(
            input = proc.inputStream,
            output = proc.outputStream,
            files = IdeFileAccess(project),
            permissions = ideaPermissionHandler(),
            listener = { },
            sessionListener = { sid, event ->
                // A "closed" from a process we shut down ourselves is no longer `process`, so it is ignored.
                if (event !is AcpEvent.Closed || process === proc) route(sid, event)
            },
        )
        c.startTimeoutSeconds = startTimeout()
        c.start()
        try {
            c.initialize()
            log.info("kiro-cli initialized in ${(System.nanoTime() - began) / 1_000_000} ms")
        } catch (e: Exception) {
            log.warn("kiro-cli handshake failed after ${(System.nanoTime() - began) / 1_000_000} ms; last output: ${stderrTail.joinToString(" | ")}")
            c.close()
            killTree(proc)
            val cause = (e as? java.util.concurrent.ExecutionException)?.cause ?: e
            val why = if (cause is java.util.concurrent.TimeoutException) {
                "kiro-cli did not answer within ${startTimeout()} s. Security software may be scanning or blocking it; " +
                    "the limit is in Settings → Tools → Kiro."
            } else {
                cause.message ?: cause.javaClass.simpleName
            }
            val output = stderrTail.joinToString("\n").takeIf { it.isNotBlank() }
            throw IllegalStateException(if (output != null) "$why\n\nkiro-cli said:\n$output" else why, e)
        }
        process = proc
        client = c
        sessionsInProcess = 0
        return c
    }

    /**
     * The v3 engine refuses to start with v2-only flags. The ones with a counterpart are carried out over ACP instead
     * (`--agent`, `--model` become the first chat's choice, `--trust-all-tools` is the `autopilot` option, on by
     * default); the rest are dropped. Returns what the chat should be told.
     */
    private fun carryOutV2Flags(args: String, engine: String): List<String> {
        if (!KiroLaunch.isV3(args, engine)) return emptyList()
        val flags = KiroLaunch.v2Flags(args)
        if (!flags.any) return emptyList()
        val notes = ArrayList<String>()
        flags.agent?.let { if (preferredMode == null) preferredMode = it; notes.add("--agent $it") }
        flags.model?.let { if (preferredModel == null) preferredModel = it; notes.add("--model $it") }
        val done = if (notes.isEmpty()) "" else "${notes.joinToString(", ")} are applied after the session opens. "
        if (flags.trustAll) trustAll = true
        val trust = if (flags.trustAll) "--trust-all-tools: the plugin approves permission requests itself now (/trust off to stop). " else ""
        val dropped = if (flags.dropped.isEmpty()) "" else "${flags.dropped.joinToString(", ")} only exist on the v2 engine and were ignored. "
        return listOf("The v3 engine doesn't take some of the arguments (kiro-cli: \"not supported with --agent-engine=v3\"). $done$trust${dropped}Choose engine v2 in Settings → Tools → Kiro to run them as typed.".trim())
    }

    private val usageLock = Any()
    private var probedUsage: Pair<Long, PlanUsage>? = null
    private var probeFailedAt = 0L

    /**
     * Plan credits through a short-lived `kiro-cli acp --agent-engine v2` process, which is closed again at once. The
     * figures are kept for [USAGE_TTL_MS], and a failed attempt is not repeated for [USAGE_RETRY_MS], so refreshing after
     * every reply never starts a process each time.
     */
    private fun usageViaV2(): PlanUsage? = synchronized(usageLock) {
        val now = System.currentTimeMillis()
        probedUsage?.takeIf { now - it.first < USAGE_TTL_MS }?.let { return it.second }
        if (now - probeFailedAt < USAGE_RETRY_MS) return null
        val settings = KiroSettings.getInstance().state
        val cwd = project.basePath ?: System.getProperty("user.home")
        val cmd = GeneralCommandLine(KiroLaunch.command(settings.kiroPath, "--agent-engine v2", settings.kiroShell))
            .withWorkDirectory(cwd)
            .withCharset(Charsets.UTF_8)
            .withEnvironment(KiroLaunch.environment(settings.kiroEnv))
        val proc = try {
            cmd.createProcess()
        } catch (e: Exception) {
            probeFailedAt = now
            return null
        }
        Thread({ proc.errorStream.bufferedReader().forEachLine { } }, "kiro-usage-stderr").apply { isDaemon = true }.start()
        val c = AcpClient(
            input = proc.inputStream,
            output = proc.outputStream,
            files = IdeFileAccess(project),
            permissions = PermissionHandler { CompletableFuture.completedFuture(null) },
            listener = { },
        )
        try {
            c.startTimeoutSeconds = startTimeout()
            c.start()
            c.initialize()
            val usage = c.usage(c.newSession(cwd).sessionId)
            if (usage != null) probedUsage = System.currentTimeMillis() to usage else probeFailedAt = System.currentTimeMillis()
            usage
        } catch (e: Exception) {
            log.info("Could not read the plan usage through the v2 engine", e)
            probeFailedAt = System.currentTimeMillis()
            null
        } finally {
            c.close()
            killTree(proc)
        }
    }

    /** A finished tool call may have changed files; the editor should show them without waiting for the turn to end. */
    private fun syncFiles(event: AcpEvent) {
        val (status, locations) = when (event) {
            is AcpEvent.ToolCall -> event.status to event.locations
            is AcpEvent.ToolCallUpdate -> event.status to event.locations
            else -> return
        }
        if (status == "completed" || status == "failed") FileSync.afterTool(locations)
    }

    private fun shutdown() {
        val c = client
        val p = process
        // Clear first, so the process's own "closed" event is recognised as expected and ignored.
        client = null
        process = null
        opening = null
        sessionsInProcess = 0
        conversations.forEach {
            it.info = null
            it.expecting = null
        }
        c?.close()
        p?.let { killTree(it) }
    }

    /**
     * Ends [p] and everything it started. On Windows `destroy()` only ends the process itself, so the engine (node)
     * and MCP servers it launched would otherwise keep running after a restart.
     */
    private fun killTree(p: Process) {
        // Collected first: once the parent is gone its children are no longer reachable through it.
        val children = try {
            p.descendants().toList()
        } catch (_: Exception) {
            emptyList()
        }
        p.destroy()
        for (child in children) {
            try {
                child.destroyForcibly()
            } catch (e: Exception) {
                log.info("Could not stop child process ${child.pid()}", e)
            }
        }
    }

    /** A helper session may use the plugin's own tools without asking; everything else (shell, edits) is refused. */
    private fun helperAnswer(request: com.kirogui.jetbrains.acp.PermissionRequest): String? {
        val ours = request.toolTitle.contains("${JobService.SERVER_NAME}/")
        val allow = request.options.firstOrNull { it.kind == "allow_once" }
            ?: request.options.firstOrNull { it.kind?.startsWith("allow") == true }
        val reject = request.options.firstOrNull { it.kind == "reject_once" }
            ?: request.options.firstOrNull { it.kind?.startsWith("reject") == true }
        return (if (ours) allow else reject)?.id
    }

    private fun ideaPermissionHandler() = PermissionHandler { request ->
        if (isHelper(request.sessionId)) return@PermissionHandler CompletableFuture.completedFuture(helperAnswer(request))
        if (trusting()) {
            val allow = request.options.firstOrNull { it.kind == "allow_once" } ?: request.options.firstOrNull { it.kind?.startsWith("allow") == true }
            if (allow != null) return@PermissionHandler CompletableFuture.completedFuture(allow.id)
        }
        val future = CompletableFuture<String?>()
        val fileAccess = IdeFileAccess(project)
        val cwd = Path.of(project.basePath ?: System.getProperty("user.home"))
        // Computed off the EDT: reading may touch the disk. Null when it isn't a file edit we understand.
        val change = try {
            EditPreview.compute(request.rawInput, cwd) { p ->
                if (Files.exists(p)) fileAccess.readText(p.toString()) else null
            }
        } catch (e: Exception) {
            log.info("Could not preview edit", e)
            null
        }
        ApplicationManager.getApplication().invokeLater {
            if (change != null && change.before != change.after) {
                val dialog = DiffReviewDialog(project, request.toolTitle, change, request.options)
                dialog.show()
                future.complete(dialog.chosen)
            } else {
                val shell = request.rawInput?.get("command")?.jsonPrimitive?.contentOrNull
                val message = if (shell.isNullOrBlank()) request.toolTitle else request.toolTitle + "\n\n" + shell
                val names = request.options.map { it.name }.toTypedArray()
                val index = Messages.showDialog(
                    project, message, "Kiro Permission Request", names, 0, Messages.getQuestionIcon(),
                )
                future.complete(request.options.getOrNull(index)?.id)
            }
        }
        future
    }

    override fun dispose() = reset()

    private companion object {
        const val MAX_SESSIONS_PER_PROCESS = 6
        const val USAGE_TTL_MS = 5 * 60_000L
        const val USAGE_RETRY_MS = 2 * 60_000L
        const val PENDING = "\u0000pending"
        val AUTH_HINT = Regex("auth|log ?in|sign ?in|credential|token|unauthori[sz]ed", RegexOption.IGNORE_CASE)
    }
}
