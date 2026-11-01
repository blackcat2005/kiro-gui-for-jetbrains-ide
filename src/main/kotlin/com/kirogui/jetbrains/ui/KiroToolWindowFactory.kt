package com.kirogui.jetbrains.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class KiroToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = KiroChatPanel(project, toolWindow.disposable)
        val factory = ContentFactory.getInstance()
        // Chat must stay the first content: the "Send to Kiro" editor action looks it up by index.
        val chat = factory.createContent(panel, "Chat", false)
        toolWindow.contentManager.addContent(chat)
        val sessions = KiroSessionsPanel(project) { session ->
            toolWindow.contentManager.setSelectedContent(chat)
            panel.loadSession(session.id, session.cwd)
        }
        toolWindow.contentManager.addContent(factory.createContent(sessions, "Sessions", false))
        toolWindow.contentManager.addContent(factory.createContent(KiroResourcesPanel(project), "Manage", false))
    }
}
