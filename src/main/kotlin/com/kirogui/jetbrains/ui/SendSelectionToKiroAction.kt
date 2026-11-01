package com.kirogui.jetbrains.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager
import java.io.File

/** Editor context menu: put the selected code (with file and line range) into the Kiro input box. */
class SendSelectionToKiroAction : AnAction(), DumbAware {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = e.project != null && editor != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val sel = editor.selectionModel
        val doc = editor.document

        val ref = file?.let { vf ->
            val base = project.basePath?.let { File(it).toPath() }
            val rel = try {
                base?.relativize(File(vf.path).toPath())?.toString()?.replace('\\', '/')
            } catch (_: IllegalArgumentException) {
                null
            } ?: vf.path
            if (sel.hasSelection()) {
                val from = doc.getLineNumber(sel.selectionStart) + 1
                val to = doc.getLineNumber(sel.selectionEnd) + 1
                "$rel:$from-$to"
            } else rel
        } ?: "selection"

        val text = if (sel.hasSelection()) {
            val lang = file?.extension.orEmpty()
            "In `$ref`:\n```$lang\n${sel.selectedText}\n```\n"
        } else {
            "Regarding `$ref`: "
        }

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Kiro") ?: return
        toolWindow.activate {
            (toolWindow.contentManager.getContent(0)?.component as? KiroChatPanel)?.appendToInput(text)
        }
    }
}
