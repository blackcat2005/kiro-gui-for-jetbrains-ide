package com.kirogui.jetbrains.service

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.kirogui.jetbrains.acp.FileAccess
import java.io.File

/** Serves fs requests from open editor buffers when available, so the agent sees unsaved edits. */
class IdeFileAccess(private val project: Project) : FileAccess {

    override fun readText(path: String): String = ReadAction.compute<String, RuntimeException> {
        val vf = LocalFileSystem.getInstance().findFileByPath(path)
        val doc = vf?.let { FileDocumentManager.getInstance().getCachedDocument(it) }
        doc?.text ?: File(path).readText()
    }

    override fun writeText(path: String, content: String) {
        ApplicationManager.getApplication().invokeAndWait {
            val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            val doc = vf?.let { FileDocumentManager.getInstance().getDocument(it) }
            if (doc != null) {
                WriteCommandAction.runWriteCommandAction(project, "Kiro: Edit ${vf.name}", null, {
                    doc.setText(content)
                    FileDocumentManager.getInstance().saveDocument(doc)
                })
            } else {
                File(path).apply { parentFile?.mkdirs() }.writeText(content)
                LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
            }
        }
    }
}
