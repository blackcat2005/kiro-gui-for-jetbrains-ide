package com.kirogui.jetbrains.ui

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

/** Builds attachments from what the IDE has open; shared by the chat's Attach menu and the editor action. */
object AttachmentSources {

    /** Project-relative path with '/' separators, or the absolute path for files outside the project. */
    fun path(project: Project, file: VirtualFile): String {
        val base = project.basePath?.let { File(it).toPath() }
        val rel = try {
            base?.relativize(File(file.path).toPath())?.toString()?.replace('\\', '/')?.takeIf { !it.startsWith("..") }
        } catch (_: IllegalArgumentException) {
            null
        }
        return rel ?: file.path.replace('\\', '/')
    }

    fun file(project: Project, file: VirtualFile) = Attachment.File(path(project, file))

    /** The selected code with its file and 1-based line range; null when nothing is selected. */
    fun selection(project: Project, editor: Editor, file: VirtualFile?): Attachment.Snippet? {
        val sel = editor.selectionModel
        val text = sel.selectedText?.takeIf { it.isNotBlank() } ?: return null
        val doc = editor.document
        val from = doc.getLineNumber(sel.selectionStart) + 1
        // A selection that ends at the start of a line did not really include that line.
        val endOffset = if (sel.selectionEnd > sel.selectionStart && doc.charsSequence[sel.selectionEnd - 1] == '\n') sel.selectionEnd - 1 else sel.selectionEnd
        val to = doc.getLineNumber(endOffset) + 1
        val where = file?.let { path(project, it) }
        val source = where?.let { if (to > from) "$it:$from-$to" else "$it:$from" }
        return Attachment.Snippet(text, file?.extension.orEmpty(), source ?: "Selection")
    }
}
