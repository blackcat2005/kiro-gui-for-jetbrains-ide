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
import com.kirogui.jetbrains.acp.PermissionHandler
import com.kirogui.jetbrains.acp.PlanUsage
import com.kirogui.jetbrains.acp.SessionInfo
import com.kirogui.jetbrains.kiro.EditPreview
import com.kirogui.jetbrains.settings.KiroSettings
import com.kirogui.jetbrains.ui.DiffReviewDialog
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Talks to one long-lived `kiro-cli acp` process per project. New chats and reopened sessions are opened on that
 * same process (about a second) instead of starting a new one each time (two to three seconds). Every session on
 * a process keeps its own MCP servers alive and ACP has no way to close one, so the process is recycled after a
 * handful of sessions.
 */
@Service(Service.Level.PROJECT)
class KiroAcpService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(KiroAcpService::class.java)
    private val listeners = CopyOnWriteArrayList<(AcpEvent) -> Unit>()

    // Read from the EDT (e.g. to highlight the open session), so never guarded by the lock that open/close hold.
    @Volatile private var process: Process? = null
    @Volatile private var client: AcpClient? = null
    @Volatile private var info: SessionInfo? = null
    private var sessionsInProcess = 0

    // Remembered across "New chat" so the user's agent/model choice sticks.
    private var preferredModel: String? = null
    private var preferredMode: String? = null

    /** Events arrive on background threads; the listener must hop to the EDT itself. */
    fun addListener(parent: Disposable, listener: (AcpEvent) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    /** The live session if there is one, else starts the process and a first session (background thread). */
    fun start(): CompletableFuture<SessionInfo> = async { ensureLive() }

    /** A fresh session on the running process. */
    fun newSession(): CompletableFuture<SessionInfo> = async { openSession(null, null) }

    /** Reloads a stored session on the running process; its history is replayed through the event listeners. */
    fun resume(sessionId: String, cwd: String): CompletableFuture<SessionInfo> = async { openSession(sessionId, cwd) }

    /** The future completes with the stop reason, or fails with the cause. */
    fun prompt(text: String): CompletableFuture<String> = async {
        val (c, session) = live()
        c.prompt(session.sessionId, text).get()
    }

    fun setModel(modelId: String): CompletableFuture<Unit> = async {
        val (c, session) = live()
        c.setModel(session.sessionId, modelId)
        synchronized(this) {
            preferredModel = modelId
            info = info?.copy(currentModelId = modelId)
        }
    }

    fun setMode(modeId: String): CompletableFuture<Unit> = async {
        val (c, session) = live()
        c.setMode(session.sessionId, modeId)
        synchronized(this) {
            preferredMode = modeId
            info = info?.copy(currentModeId = modeId)
        }
    }

    /** Account-wide plan credits; null if Kiro can't say (not signed in, no cap info). */
    fun usage(): CompletableFuture<PlanUsage?> = async {
        val (c, session) = live()
        c.usage(session.sessionId)
    }

    /** The id of the live session, or null before one is started. Cheap and lock-free. */
    fun currentSessionId(): String? = info?.sessionId

    fun cancel() {
        val c = client
        val s = info
        if (c != null && s != null) c.cancel(s.sessionId)
    }

    /** Stops the process; the next call starts a fresh one. */
    @Synchronized
    fun reset() = shutdown()

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

    private fun live(): Pair<AcpClient, SessionInfo> {
        val c = client
        val s = info
        if (c != null && s != null) return c to s
        ensureLive()
        return client!! to info!!
    }

    /** Idempotent under the lock, so a "start" racing a first prompt can't open two sessions. */
    @Synchronized
    private fun ensureLive(): SessionInfo = info ?: openSession(null, null)

    @Synchronized
    private fun openSession(resumeId: String?, cwdOverride: String?): SessionInfo {
        val cwd = cwdOverride ?: project.basePath ?: System.getProperty("user.home")
        var c = client
        if (c == null || sessionsInProcess >= MAX_SESSIONS_PER_PROCESS) {
            shutdown()
            c = launch(cwd)
        }
        val previous = info?.sessionId
        // While opening, only the target session's events may reach the UI. A new session's id isn't known yet.
        c.activeSession = resumeId ?: PENDING
        var session = try {
            if (resumeId != null) c.loadSession(resumeId, cwd) else c.newSession(cwd)
        } catch (e: Exception) {
            c.activeSession = previous
            throw e
        }
        // A reloaded session keeps its own agent and model; only fresh sessions get the remembered choice.
        if (resumeId == null) {
            preferredMode?.takeIf { it != session.currentModeId && session.modes.any { m -> m.id == it } }?.let {
                c.setMode(session.sessionId, it)
                session = session.copy(currentModeId = it)
            }
            preferredModel?.takeIf { it != session.currentModelId && session.models.any { m -> m.id == it } }?.let {
                c.setModel(session.sessionId, it)
                session = session.copy(currentModelId = it)
            }
        }
        c.activeSession = session.sessionId
        info = session
        sessionsInProcess++
        return session
    }

    private fun launch(cwd: String): AcpClient {
        val cmd = GeneralCommandLine(KiroSettings.getInstance().state.kiroPath, "acp")
            .withWorkDirectory(cwd)
            .withCharset(Charsets.UTF_8)
        val proc = cmd.createProcess()
        Thread({
            proc.errorStream.bufferedReader().forEachLine { log.info("kiro-cli: $it") }
        }, "kiro-cli-stderr").apply { isDaemon = true }.start()

        val c = AcpClient(
            input = proc.inputStream,
            output = proc.outputStream,
            files = IdeFileAccess(project),
            permissions = ideaPermissionHandler(),
            listener = { event ->
                if (event !is AcpEvent.Closed) {
                    listeners.forEach { it(event) }
                } else if (process === proc) {
                    // The live process died on its own. (One we shut down ourselves is no longer `process`.)
                    process = null
                    client = null
                    info = null
                    sessionsInProcess = 0
                    listeners.forEach { it(event) }
                }
            },
        )
        c.start()
        try {
            c.initialize()
        } catch (e: Exception) {
            c.close()
            proc.destroy()
            throw e
        }
        process = proc
        client = c
        sessionsInProcess = 0
        return c
    }

    private fun shutdown() {
        val c = client
        val p = process
        // Clear first, so the process's own "closed" event is recognised as expected and ignored.
        client = null
        process = null
        info = null
        sessionsInProcess = 0
        c?.close()
        p?.destroy()
    }

    private fun ideaPermissionHandler() = PermissionHandler { request ->
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
        const val PENDING = "\u0000pending"
    }
}
