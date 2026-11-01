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

/** One `kiro-cli acp` process and one ACP session per project, started lazily. */
@Service(Service.Level.PROJECT)
class KiroAcpService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(KiroAcpService::class.java)
    private val listeners = CopyOnWriteArrayList<(AcpEvent) -> Unit>()

    private var process: Process? = null
    private var client: AcpClient? = null
    private var info: SessionInfo? = null

    // Remembered across "New chat" so the user's agent/model choice sticks.
    private var preferredModel: String? = null
    private var preferredMode: String? = null

    /** Events arrive on background threads; the listener must hop to the EDT itself. */
    fun addListener(parent: Disposable, listener: (AcpEvent) -> Unit) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    /** Starts the process/session if needed (background thread). Completes with the agents and models on offer. */
    fun start(): CompletableFuture<SessionInfo> = async { ensureSession().second }

    /** Drops the current session and reloads a stored one; its history is replayed through the event listeners. */
    fun resume(sessionId: String, cwd: String): CompletableFuture<SessionInfo> = async {
        reset()
        ensureSession(resumeId = sessionId, cwdOverride = cwd).second
    }

    /** The future completes with the stop reason, or fails with the cause. */
    fun prompt(text: String): CompletableFuture<String> = async {
        val (c, session) = ensureSession()
        c.prompt(session.sessionId, text).get()
    }

    fun setModel(modelId: String): CompletableFuture<Unit> = async {
        val (c, session) = ensureSession()
        c.setModel(session.sessionId, modelId)
        synchronized(this) {
            preferredModel = modelId
            info = info?.copy(currentModelId = modelId)
        }
    }

    fun setMode(modeId: String): CompletableFuture<Unit> = async {
        val (c, session) = ensureSession()
        c.setMode(session.sessionId, modeId)
        synchronized(this) {
            preferredMode = modeId
            info = info?.copy(currentModeId = modeId)
        }
    }

    fun cancel() {
        val c = client
        val s = info
        if (c != null && s != null) c.cancel(s.sessionId)
    }

    /** Drops the current process/session; the next call starts a fresh one. */
    @Synchronized
    fun reset() {
        client?.close()
        process?.destroy()
        client = null
        process = null
        info = null
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

    @Synchronized
    private fun ensureSession(resumeId: String? = null, cwdOverride: String? = null): Pair<AcpClient, SessionInfo> {
        client?.let { c -> info?.let { return c to it } }

        val cwd = cwdOverride ?: project.basePath ?: System.getProperty("user.home")
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
                if (event is AcpEvent.Closed) synchronized(this) {
                    if (process === proc) {
                        client = null
                        process = null
                        info = null
                    }
                }
                listeners.forEach { it(event) }
            },
        )
        c.start()
        try {
            c.initialize()
            var session = if (resumeId != null) c.loadSession(resumeId, cwd) else c.newSession(cwd)
            // A reloaded session keeps its own agent and model; only fresh sessions get the remembered choice.
            if (resumeId == null) preferredMode?.takeIf { it != session.currentModeId && session.modes.any { m -> m.id == it } }?.let {
                c.setMode(session.sessionId, it)
                session = session.copy(currentModeId = it)
            }
            if (resumeId == null) preferredModel?.takeIf { it != session.currentModelId && session.models.any { m -> m.id == it } }?.let {
                c.setModel(session.sessionId, it)
                session = session.copy(currentModelId = it)
            }
            process = proc
            client = c
            info = session
            return c to session
        } catch (e: Exception) {
            c.close()
            proc.destroy()
            throw e
        }
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
}
