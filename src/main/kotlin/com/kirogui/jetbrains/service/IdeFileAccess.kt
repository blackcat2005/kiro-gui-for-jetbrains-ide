package com.kirogui.jetbrains.service

import com.intellij.ide.SaveAndSyncHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.kirogui.jetbrains.acp.FileAccess
import java.io.File
import java.net.URI
import java.nio.file.Path

/** Serves fs requests from open editor buffers when available, so the agent sees unsaved edits. */
class IdeFileAccess(private val project: Project) : FileAccess {

    override fun readText(path: String): String {
        val p = IdePaths.normalize(path)
        return runReadAction {
            val vf = LocalFileSystem.getInstance().findFileByPath(p)
            val doc = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
            doc?.text ?: File(p).readText()
        }
    }

    override fun writeText(path: String, content: String) {
        val p = IdePaths.normalize(path)
        // Default modality on purpose: document changes are not allowed from a "modality any" context.
        ApplicationManager.getApplication().invokeAndWait {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(p)
            val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (doc != null) {
                WriteCommandAction.runWriteCommandAction(project, "Kiro: Edit ${vf.name}", null, {
                    doc.setText(content)
                    FileDocumentManager.getInstance().saveDocument(doc)
                })
            } else {
                File(p).apply { parentFile?.mkdirs() }.writeText(content)
                LocalFileSystem.getInstance().refreshAndFindFileByPath(p)
            }
        }
    }
}

/**
 * Paths as kiro-cli sends them, made into the form the IDE's file system uses. kiro-cli 3.x writes Windows paths
 * with a lower-case drive (`d:\src\A.kt`) while the IDE knows the file as `D:\src\A.kt`; looked up as sent, the
 * IDE can treat it as another file, so an edit lands on disk but the open editor keeps showing the old text.
 */
object IdePaths {
    fun normalize(path: String, windows: Boolean = File.separatorChar == '\\'): String {
        var p = path.trim()
        if (p.startsWith("file:")) p = try {
            Path.of(URI(p)).toString()
        } catch (_: Exception) {
            p
        }
        if (!windows) return p
        if (p.length >= 2 && p[1] == ':' && p[0].isLetter()) p = p[0].uppercaseChar() + p.substring(1)
        // On Windows the canonical form also has each folder's real letter case. (Not elsewhere: it resolves symlinks.)
        return try {
            File(p).canonicalPath
        } catch (_: Exception) {
            p
        }
    }
}

/**
 * Tells the IDE about files Kiro changed. Edits through `fs/write_text_file` update the editor at once, but Kiro can
 * also change files itself (shell commands, its own tools), and the IDE otherwise only notices when its window is
 * activated again.
 */
object FileSync {

    /** After a tool call finished: re-read the files it names, or everything that changed if it names none. */
    fun afterTool(locations: List<String>) {
        val paths = locations.mapNotNull {
            try {
                Path.of(IdePaths.normalize(it))
            } catch (_: Exception) {
                null
            }
        }
        if (paths.isEmpty()) syncAll()
        else LocalFileSystem.getInstance().refreshNioFiles(paths, true, false, null)
    }

    /** Picks up every change on disk the IDE has not seen yet (what activating the IDE window does). */
    fun syncAll() {
        ApplicationManager.getApplication().invokeLater({
            SaveAndSyncHandler.getInstance().scheduleRefresh()
        }, ModalityState.any())
    }
}
