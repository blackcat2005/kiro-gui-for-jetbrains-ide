package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.tabs.JBTabsFactory
import com.intellij.ui.tabs.TabInfo
import com.intellij.ui.tabs.TabsListener
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.kiro.KiroSession
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * The whole Kiro tool window: one list of sessions and library on the left, the chats as tabs in the middle, the
 * background jobs on the right (underneath when the window is narrow). The list and the jobs are shared by every chat,
 * so opening more chats costs nothing but the chat itself.
 *
 * ```
 * | sessions + library | [chat] [chat 2] [chat 3] | background jobs |
 * ```
 */
class KiroShell(private val project: Project, private val disposable: Disposable) : JPanel(BorderLayout()) {

    private class Chat(val info: TabInfo, val panel: KiroChatPanel, val disposable: Disposable)

    private val tabs = JBTabsFactory.createTabs(project, disposable)
    private val chats = ArrayList<Chat>()
    private var counter = 0
    private var jobsShown = false
    private var activeJobs = 0

    private val body = JPanel(BorderLayout())

    // The tool window's own title bar drops its actions in floating and windowed mode, so the buttons live in the panel,
    // at the right end of the tab strip. A Swing component has one parent, so every tab gets its own set; they stay in step.
    private val libraryToggles = ArrayList<PillButton>()
    private val jobsToggles = ArrayList<PillButton>()

    private fun jobsLabel() = if (activeJobs > 0) "Jobs · $activeJobs" else "Jobs"

    private fun jobsTip() =
        if (activeJobs > 0) "Background jobs: $activeJobs running" else "Background jobs (right side): commands, tests and debug sessions Kiro started"

    private fun syncToggles() {
        libraryToggles.forEach { it.selected = libraryShown }
        jobsToggles.forEach {
            it.selected = jobsShown
            it.setLabel(jobsLabel(), jobsTip())
        }
    }

    private var libraryShown = PropertiesComponent.getInstance().getBoolean(KEY_LIBRARY, false)

    private val jobsButton = PillButton(
        AllIcons.Toolwindows.ToolWindowRun, jobsLabel(), jobsTip(), toggle = true, selected = jobsShown,
    ) { setJobsShown(it) }.also { jobsToggles.add(it) }

    private val sessionsButton = PillButton(
        KiroIcons.Session, "Sessions",
        "Sessions and Library (left side): sessions, skills, steering files, specs, hooks and agents", toggle = true, selected = libraryShown,
    ) { setLibraryShown(it) }.also { libraryToggles.add(it) }

    private val chipsRow = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
        isOpaque = false
    }

    /**
     * One header row, as tall as the sessions list's: New chat, Sessions and Refresh at the left, then a chip per chat
     * (the open one stands out), Jobs at the right. The tab strip of [tabs] is hidden; it only holds the chats.
     */
    private val header: JPanel = JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
        border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(0, 4))
        preferredSize = Dimension(0, JBUI.scale(PANEL_HEADER_HEIGHT))
        fun buttons(vararg parts: JComponent) = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            parts.forEach { add(it) }
            maximumSize = Dimension(preferredSize.width, JBUI.scale(PANEL_HEADER_HEIGHT))
            alignmentY = 0.5f
        }
        // Left to right: New chat, Sessions, Refresh, then the chat tabs right beside them; Jobs alone at the far right.
        add(buttons(
            PillButton(AllIcons.General.Add, "New chat", "Open a new chat in a tab; chats work side by side") { newChatTab() },
            sessionsButton,
            iconButton(AllIcons.Actions.Refresh, "Restart kiro-cli for this chat") { selectedChat()?.restart() },
        ))
        chipsRow.alignmentY = 0.5f
        add(chipsRow)
        add(javax.swing.Box.createHorizontalGlue())
        add(buttons(jobsButton))
    }

    /** A chat in the header: its name (and spinner while busy) and a close button; the open chat is accent-coloured on a tint. */
    private fun chip(chat: Chat): JComponent {
        val on = chat.info === tabs.selectedInfo
        val accent = KiroChatPanel.ACCENT
        val label = com.intellij.ui.components.JBLabel(chat.info.text, chat.info.icon, javax.swing.SwingConstants.LEFT).apply {
            if (on) {
                font = font.deriveFont(java.awt.Font.BOLD)
                foreground = accent
            }
        }
        val chip = object : JPanel(BorderLayout(JBUI.scale(4), 0)) {
            override fun paintComponent(g: java.awt.Graphics) {
                if (on) {
                    g.color = com.intellij.ui.ColorUtil.mix(com.intellij.util.ui.UIUtil.getPanelBackground(), accent, 0.18)
                    g.fillRect(0, 0, width, height)
                    g.color = accent
                    g.fillRect(0, height - JBUI.scale(2), width, JBUI.scale(2))
                }
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            toolTipText = chat.info.text
            add(label, BorderLayout.CENTER)
            // The only chat can be closed too (it is replaced by an empty one), unless it already is empty.
            if (chats.size > 1 || !chat.panel.isEmptyChat) {
                val tip = if (chats.size > 1) "Close this chat (the session stays in the list)" else "Close this chat and start an empty one (the session stays in the list)"
                add(iconButton(AllIcons.Actions.Close, tip) { close(chat) }, BorderLayout.EAST)
            }
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (e.button == java.awt.event.MouseEvent.BUTTON1) tabs.select(chat.info, true)
                }
            })
        }
        chip.maximumSize = Dimension(chip.preferredSize.width, Int.MAX_VALUE) // full header height, so the underline sits at the bottom
        return chip
    }

    private var chipsShown = ""

    private fun rebuildChips() {
        // The session list refreshes while a chat streams, and every refresh came here: new components under the mouse
        // between press and release swallowed the click on a chip's close button. Redraw only when something shows differently.
        val signature = chats.joinToString("|") {
            "${System.identityHashCode(it)}:${it.info.text}:${it.info === tabs.selectedInfo}:${System.identityHashCode(it.info.icon)}:${chats.size > 1 || !it.panel.isEmptyChat}"
        }
        if (signature == chipsShown) return
        chipsShown = signature
        chipsRow.removeAll()
        chats.forEach {
            chipsRow.add(chip(it))
            chipsRow.add(javax.swing.Box.createHorizontalStrut(JBUI.scale(2)))
        }
        chipsRow.add(javax.swing.Box.createHorizontalGlue())
        chipsRow.revalidate()
        chipsRow.repaint()
    }

    private val library = KiroLibraryPanel(
        project,
        onOpenSession = ::open,
        onUseSkill = { name ->
            selectedChat()?.prefillInput("/$name ")
        },
        currentSessionId = { selectedChat()?.currentSessionId() },
        currentAgent = { selectedChat()?.currentAgentId() },
        onOpenSessionInNewTab = ::openInNewTab,
        onNewChatButton = ::newChat,
    ).apply { minimumSize = Dimension(JBUI.scale(180), 0) }

    private val jobs = KiroJobsPanel(
        project,
        disposable,
        currentSessionId = { selectedChat()?.currentSessionId() },
        onAttach = { selectedChat()?.attach(it) },
        onActiveCount = ::jobCountChanged,
        onHide = { setJobsShown(false) },
    ).apply { minimumSize = Dimension(JBUI.scale(200), JBUI.scale(90)) }

    /** Wide enough for the jobs to sit beside the chat rather than under it. */
    private var wide = false

    init {
        tabs.addListener(object : TabsListener {
            override fun selectionChanged(oldSelection: TabInfo?, newSelection: TabInfo?) {
                library.refresh() // moves the highlight to the chat's session
                rebuildChips()
                saveChats()
                (newSelection?.component as? KiroChatPanel)?.requestInputFocus()
            }
        }, disposable)
        library.onLoaded = ::refreshTitles
        addChat()
        restoreChats()
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                val nowWide = width >= JBUI.scale(WIDE_AT)
                if (nowWide != wide) {
                    wide = nowWide
                    if (jobsShown) layoutContent()
                }
            }
        })
        tabs.presentation.isHideTabs = true
        add(body, BorderLayout.CENTER)
        layoutContent()
    }

    // ---------- chats ----------

    /** True while the chats of last time are being put back, so the half-restored state is not saved over the real one. */
    private var restoring = false

    private fun saveChats() {
        if (restoring) return
        val store = PropertiesComponent.getInstance(project)
        store.setValue(KEY_CHATS, chats.mapNotNull { it.panel.currentSessionId() }.joinToString("\n"), "")
        store.setValue(KEY_SELECTED, chats.firstOrNull { it.info === tabs.selectedInfo }?.panel?.currentSessionId() ?: "", "")
    }

    /** Opens the sessions that were open when the project was last closed: the first in the empty chat, the rest in tabs of their own. */
    private fun restoreChats() {
        if (!com.kirogui.jetbrains.settings.KiroSettings.getInstance().state.restoreChats) return
        val store = PropertiesComponent.getInstance(project)
        val ids = store.getValue(KEY_CHATS, "").split('\n').filter { it.isNotBlank() }.take(MAX_RESTORED)
        if (ids.isEmpty()) return
        val selected = store.getValue(KEY_SELECTED, "")
        restoring = true
        ApplicationManager.getApplication().executeOnPooledThread {
            val found = try {
                val all = com.kirogui.jetbrains.kiro.SessionScanner(java.nio.file.Path.of(System.getProperty("user.home"), ".kiro")).scan().associateBy { it.id }
                ids.mapNotNull(all::get)
            } catch (e: Exception) {
                emptyList()
            }
            ApplicationManager.getApplication().invokeLater({
                try {
                    // The user may already have typed in the first chat while the list was read: then leave things alone.
                    if (found.isNotEmpty() && chats.size == 1 && chats.first().panel.isEmptyChat) {
                        found.forEachIndexed { i, session -> (if (i == 0) chats.first() else addChat()).panel.openStored(session) }
                        chats.firstOrNull { it.panel.currentSessionId() == selected }?.let { tabs.select(it.info, true) }
                    }
                } finally {
                    restoring = false
                    refreshTitles()
                    saveChats()
                }
            }, { project.isDisposed })
        }
    }

    fun selectedChat(): KiroChatPanel? {
        val selected = tabs.selectedInfo
        return chats.firstOrNull { it.info === selected }?.panel ?: chats.firstOrNull()?.panel
    }

    /** A new chat tab (named by its first question once there is one). */
    private fun addChat(): Chat {
        val chatDisposable = Disposer.newDisposable(disposable, "KiroChat")
        val panel = KiroChatPanel(project, chatDisposable)
        counter++
        val info = TabInfo(panel).setText("Chat $counter").setIcon(KiroIcons.Session).setDefaultAttributes(com.intellij.ui.SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES.toTextAttributes())
        val chat = Chat(info, panel, chatDisposable)
        info.setTabLabelActions(
            DefaultActionGroup(
                object : DumbAwareAction("Close Chat", "Close this chat tab (the session stays in the list)", AllIcons.Actions.Close) {
                    override fun actionPerformed(e: AnActionEvent) = close(chat)

                    override fun update(e: AnActionEvent) {
                        e.presentation.isVisible = chats.size > 1
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.EDT
                },
            ),
            "KiroChatTab",
        )
        panel.minimumSize = Dimension(JBUI.scale(260), 0)
        panel.onShowJobs = { setJobsShown(!jobsShown) }
        panel.onSessionsChanged = {
            refreshTitles()
            library.refresh()
        }
        panel.onBusyChanged = { busy ->
            ApplicationManager.getApplication().invokeLater {
                info.setIcon(if (busy) AnimatedIcon.Default.INSTANCE else KiroIcons.Session)
                refreshTitles() // also redraws the chips
            }
        }
        chats.add(chat)
        tabs.addTab(info)
        tabs.select(info, true)
        rebuildChips()
        return chat
    }

    private fun close(chat: Chat) {
        try {
            closeChat(chat)
        } catch (e: Throwable) {
            com.intellij.openapi.diagnostic.Logger.getInstance(KiroShell::class.java).warn("Closing a chat failed", e)
            chipsShown = ""
            refreshTitles()
        }
    }

    private fun closeChat(chat: Chat) {
        if (chat.panel.isBusy && Messages.showYesNoDialog(
                project, "Kiro is still working in this chat. Stop it and close the tab?", "Kiro", Messages.getWarningIcon(),
            ) != Messages.YES
        ) return
        chat.panel.stop()
        if (chats.size <= 1) {
            // There must be one chat tab: closing the last one leaves a fresh, empty chat in its place.
            chat.panel.newChat()
            refreshTitles()
            rebuildChips()
            return
        }
        val index = chats.indexOf(chat)
        if (index < 0) return // already closed (a second click on a chip that was not redrawn yet)
        val wasSelected = chat.info === tabs.selectedInfo
        chats.remove(chat)
        tabs.removeTab(chat.info)
        Disposer.dispose(chat.disposable)
        if (wasSelected) chats.getOrNull((index - 1).coerceAtLeast(0))?.let { tabs.select(it.info, true) }
        // Closing a chat that was not in front changes no selection, so nothing else would redraw the header.
        refreshTitles()
        rebuildChips()
    }

    /** "New chat": the tab you are in if it is still empty, else a tab of its own. */
    fun newChat() {
        val current = chats.firstOrNull { it.info === tabs.selectedInfo }
        if (current != null && current.panel.isEmptyChat) {
            current.panel.requestInputFocus()
            return
        }
        addChat()
    }

    /** A session from the list: the tab that has it, else the tab you are in (unless it is busy), else a new tab. */
    fun open(session: KiroSession) {
        chats.firstOrNull { it.panel.currentSessionId() == session.id }?.let { tabs.select(it.info, true); return }
        val here = chats.firstOrNull { it.info === tabs.selectedInfo }
        if (here == null || here.panel.isBusy) openInNewTab(session) else here.panel.openStored(session)
    }

    fun openInNewTab(session: KiroSession) {
        chats.firstOrNull { it.panel.currentSessionId() == session.id }?.let { tabs.select(it.info, true); return }
        val current = chats.firstOrNull { it.info === tabs.selectedInfo }
        val chat = if (current != null && current.panel.isEmptyChat) current else addChat()
        chat.panel.openStored(session)
    }

    private fun refreshTitles() {
        chats.forEachIndexed { i, chat ->
            // The name the list shows (your rename, else Kiro's title), else the first question, else "Chat N".
            val named = chat.panel.currentSessionId()?.let(library::sessionName)
                ?: chat.panel.firstPrompt()?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
            val title = named?.let { if (it.length > TITLE_CHARS) it.take(TITLE_CHARS - 1) + "…" else it } ?: "Chat ${i + 1}"
            if (chat.info.text != title) chat.info.setText(title)
        }
        rebuildChips()
        saveChats()
    }

    // ---------- layout ----------

    fun setLibraryShown(value: Boolean) {
        if (value == libraryShown) return
        libraryShown = value
        PropertiesComponent.getInstance().setValue(KEY_LIBRARY, value, true)
        layoutContent()
    }

    fun setJobsShown(value: Boolean) {
        if (value == jobsShown) return
        jobsShown = value
        if (value) jobs.select(null)
        layoutContent()
    }

    private fun jobCountChanged(active: Int) {
        // A job that starts is worth a look; one that ends doesn't pop anything up or away.
        val started = active > activeJobs
        activeJobs = active
        syncToggles()
        if (started && !jobsShown) setJobsShown(true) else if (started) layoutContent() // widen from the empty state
    }

    private fun newChatTab() {
        chats.firstOrNull { it.info === tabs.selectedInfo }?.takeIf { it.panel.isEmptyChat }?.let { newChat(); return }
        addChat()
    }

    private fun layoutContent() {
        body.removeAll()
        syncToggles()
        val chatsArea: JComponent = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(tabs.component, BorderLayout.CENTER)
        }
        val middle: JComponent = if (jobsShown) {
            // No jobs yet: the panel only has an empty message to show, so it stays slim until Kiro starts one.
            val empty = project.getService(com.kirogui.jetbrains.jobs.JobService::class.java).all().isEmpty()
            val key = (if (wide) "kirogui.jobs.wide" else "kirogui.jobs.narrow") + if (empty) ".empty" else ""
            val share = if (empty) (if (wide) 0.84f else 0.8f) else if (wide) 0.7f else 0.62f
            OnePixelSplitter(!wide, key, share).apply {
                setHonorComponentsMinimumSize(true)
                firstComponent = chatsArea
                secondComponent = jobs
            }
        } else {
            chatsArea
        }
        val inner: JComponent = if (libraryShown) {
            OnePixelSplitter(false, "kirogui.library", 0.24f).apply {
                setHonorComponentsMinimumSize(true)
                firstComponent = library
                secondComponent = middle
            }
        } else {
            middle
        }
        body.add(inner, BorderLayout.CENTER)
        body.revalidate()
        body.repaint()
    }

    // ---------- tool window title buttons ----------

    fun titleActions(): List<AnAction> = listOf(
        object : DumbAwareAction("New Chat", "Open a chat in a new tab; chats work side by side", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) {
                chats.firstOrNull { it.info === tabs.selectedInfo }?.takeIf { it.panel.isEmptyChat }?.let { newChat(); return }
                addChat()
            }
        },
        toggle("Sessions and Library", "Show or hide the list of sessions, skills, steering files and specs", KiroIcons.Session, { libraryShown }) {
            setLibraryShown(it)
        },
        object : ToggleAction("Background Jobs", "Show or hide the background jobs", AllIcons.Toolwindows.ToolWindowRun), DumbAware {
            override fun isSelected(e: AnActionEvent) = jobsShown

            override fun setSelected(e: AnActionEvent, state: Boolean) = setJobsShown(state)

            override fun update(e: AnActionEvent) {
                super.update(e)
                e.presentation.description = if (activeJobs > 0) "Background jobs: $activeJobs running" else "Show or hide the background jobs"
            }

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        },
    )

    private fun toggle(text: String, description: String, icon: javax.swing.Icon, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, description, icon), DumbAware {
            override fun isSelected(e: AnActionEvent) = get()

            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)

            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    private companion object {
        const val KEY_LIBRARY = "kirogui.chat.sidebar.v2"
        const val KEY_CHATS = "kirogui.chat.open"
        const val KEY_SELECTED = "kirogui.chat.selected"
        const val MAX_RESTORED = 5
        const val WIDE_AT = 1000
        const val TITLE_CHARS = 24
    }
}
