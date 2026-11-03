package com.kirogui.jetbrains.ui

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.wm.ToolWindowManager

/**
 * Editor context menu: attach the selected code (with file and line range) to the Kiro chat as a snippet chip,
 * kept apart from the prompt you type. With nothing selected the whole file is attached.
 */
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
        val item = AttachmentSources.selection(project, editor, file)
            ?: file?.let { AttachmentSources.file(project, it) }
            ?: return

        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Kiro") ?: return
        toolWindow.activate {
            (toolWindow.contentManager.getContent(0)?.component as? KiroChatHost)?.chat?.attach(item)
        }
    }
}
