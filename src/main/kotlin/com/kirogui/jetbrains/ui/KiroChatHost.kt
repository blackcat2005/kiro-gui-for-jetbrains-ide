package com.kirogui.jetbrains.ui

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBSplitter
import com.kirogui.jetbrains.kiro.KiroSession
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/**
 * The Chat tab: the conversation with the session list beside it, so switching to an older session, starting a
 * new one and filing sessions into folders all happen without leaving the chat. The list can be hidden from the
 * chat header (the choice is remembered), and the tool window can be docked at the bottom for a wide layout.
 */
class KiroChatHost(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    val chat = KiroChatPanel(project, parent)

    private val sessions = KiroLibraryPanel(
        project,
        onOpenSession = ::openSession,
        onUseSkill = {},
        compact = true,
        onNewChat = ::startNewChat,
        currentSessionId = { chat.currentSessionId() },
    ).apply { minimumSize = Dimension(170, 0) }

    private val splitter = JBSplitter(false, 0.32f).apply {
        firstComponent = sessions
        setHonorComponentsMinimumSize(true)
    }

    private var shown = PropertiesComponent.getInstance().getBoolean(KEY, true)

    init {
        chat.minimumSize = Dimension(260, 0)
        chat.sidebar = object : KiroChatPanel.SidebarToggle {
            override var shown: Boolean
                get() = this@KiroChatHost.shown
                set(value) = setShown(value)
        }
        chat.onSessionsChanged = { sessions.refresh() }
        layoutContent()
    }

    /** Opens a stored session in the chat, asking first if Kiro is still answering. */
    fun openSession(session: KiroSession) {
        if (session.id == chat.currentSessionId()) return
        if (!confirmStop("switch to another session")) return
        chat.openStored(session)
    }

    private fun startNewChat() {
        if (!confirmStop("start a new chat")) return
        chat.newChat()
    }

    private fun confirmStop(what: String): Boolean {
        if (!chat.isBusy) return true
        val yes = Messages.showYesNoDialog(project, "Kiro is still working on a reply. Stop it and $what?", "Kiro", Messages.getWarningIcon()) == Messages.YES
        if (yes) chat.stop()
        return yes
    }

    private fun setShown(value: Boolean) {
        if (value == shown) return
        shown = value
        PropertiesComponent.getInstance().setValue(KEY, value, true)
        layoutContent()
    }

    private fun layoutContent() {
        splitter.secondComponent = null
        removeAll()
        if (shown) {
            splitter.secondComponent = chat
            add(splitter, BorderLayout.CENTER)
        } else {
            add(chat, BorderLayout.CENTER)
        }
        revalidate()
        repaint()
    }

    private companion object {
        const val KEY = "kirogui.chat.sidebar"
    }
}
