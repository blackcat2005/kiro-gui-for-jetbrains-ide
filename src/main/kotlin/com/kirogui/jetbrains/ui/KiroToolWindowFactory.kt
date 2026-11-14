package com.kirogui.jetbrains.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class KiroToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val disposable = Disposer.newDisposable(toolWindow.disposable, "KiroShell")
        val shell = KiroShell(project, disposable)
        // One content: the chats are tabs inside it, next to the shared session list and jobs.
        val content = ContentFactory.getInstance().createContent(shell, "", false)
        content.isCloseable = false
        content.setDisposer(disposable)
        toolWindow.contentManager.addContent(content)
    }
}
