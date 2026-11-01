package com.kirogui.jetbrains.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory

class KiroToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val host = KiroChatHost(project, toolWindow.disposable)
        val factory = ContentFactory.getInstance()
        // Chat must stay the first content: the "Send to Kiro" editor action looks it up by index.
        val chat = factory.createContent(host, "Chat", false)
        toolWindow.contentManager.addContent(chat)

        val library = KiroLibraryPanel(
            project,
            onOpenSession = { session ->
                toolWindow.contentManager.setSelectedContent(chat)
                host.openSession(session)
            },
            onUseSkill = { name ->
                toolWindow.contentManager.setSelectedContent(chat)
                host.chat.prefillInput("/$name ")
            },
            currentSessionId = { host.chat.currentSessionId() },
        )
        toolWindow.contentManager.addContent(factory.createContent(library, "Library", false))
    }
}
