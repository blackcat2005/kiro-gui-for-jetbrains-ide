package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kirogui.jetbrains.acp.AcpEvent
import com.kirogui.jetbrains.acp.Choice
import com.kirogui.jetbrains.acp.PlanUsage
import com.kirogui.jetbrains.acp.SessionInfo
import com.kirogui.jetbrains.acp.UsageLevel
import com.kirogui.jetbrains.acp.SlashCommand
import com.kirogui.jetbrains.kiro.CompletionEngine
import com.kirogui.jetbrains.kiro.FileResolver
import com.kirogui.jetbrains.kiro.KiroSession
import com.kirogui.jetbrains.kiro.PreviewItem
import com.kirogui.jetbrains.kiro.TranscriptReader
import com.kirogui.jetbrains.kiro.KiroResources
import com.kirogui.jetbrains.kiro.SkillRef
import com.kirogui.jetbrains.service.KiroAcpService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.event.HyperlinkEvent

/**
 * Chat view: Markdown transcript with tool-call status rows, agent/model pickers in a header toolbar,
 * and an auto-growing input. Enter sends, Shift+Enter inserts a newline; one button toggles Send / Stop.
 */
class KiroChatPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private val service = project.getService(KiroAcpService::class.java)
    private val blocks = mutableListOf<ChatBlock>()

    private val transcript = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        addHyperlinkListener { e -> onLink(e) }
    }
    private val scroll = JBScrollPane(transcript).apply { border = JBUI.Borders.empty() }

    private val input = JBTextArea(2, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 6)
        emptyText.text = DEFAULT_HINT
    }
    private val actionButton = JButton("Send", AllIcons.Actions.Execute)
    private val status = JBLabel(" ")

    // Account-wide plan credits, top right of the header: a small bar plus "30.6 / 50 credits · 61%".
    private val planBar = UsageBar()
    private val planLabel = JBLabel(" ")
    private val planPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
        isVisible = false // until Kiro has told us
        cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
        add(planBar)
        add(planLabel)
    }
    private var lastUsageFetch = 0L

    private val agentBox = ComboBox<Choice>().apply { renderer = choiceRenderer("Agent"); toolTipText = "Agent" }
    private val modelBox = ComboBox<Choice>().apply { renderer = choiceRenderer("Model"); toolTipText = "Model" }
    private lateinit var headerToolbar: javax.swing.JComponent
    private lateinit var headerPanel: JPanel
    private var plan: PlanUsage? = null

    private var commands: List<SlashCommand> = emptyList()
    private var populating = false
    private var busy = false
    private var transient: String? = null
    private var usageText = ""
    private var credits = 0.0

    // Which conversation is on screen. Callbacks from earlier requests compare `generation` and drop themselves.
    private var generation = 0
    private var opening = false // a session is being created or reloaded; sending now would reach the wrong one
    private var viewOnly = false // a stored session Kiro can't continue is on screen
    private var replayCovered = false // history already shown from the files, so the agent's replay is redundant
    private var displayedId: String? = null

    /** Lets the host show/hide the session sidebar; the header toggle only appears once this is set. */
    interface SidebarToggle {
        var shown: Boolean
    }

    var sidebar: SidebarToggle? = null

    /** Called whenever the set of stored sessions probably changed (a turn finished, a session was created or opened). */
    var onSessionsChanged: (() -> Unit)? = null

    val isBusy: Boolean get() = busy

    /** The session on screen: the one being viewed, else the live one. */
    fun currentSessionId(): String? = displayedId ?: service.currentSessionId()

    /** Stops the running turn, if any. */
    fun stop() = service.cancel()

    private val files = ProjectFileList(project)
    private val resolver = FileResolver(
        files = { files.get() },
        base = project.basePath?.replace('\\', '/')?.trimEnd('/'),
        absoluteExists = { java.io.File(it).isFile },
    )
    private var skillsCache: List<SkillRef> = emptyList()
    private var skillsLoadedAt = 0L
    private val completion = InputCompletion(
        input,
        CompletionEngine(commands = { commands }, skills = { skills() }, files = { files.get() }),
        parent,
    )

    private val renderTimer = Timer(60) { renderTranscript() }.apply { isRepeats = false }

    init {
        add(header(), BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(composer(), BorderLayout.SOUTH)

        actionButton.addActionListener { if (busy) service.cancel() else send() }
        planPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = refreshPlanUsage(force = true)
        })
        completion.install()
        files.get() // start scanning now so the first "@" already has results
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (completion.handleKey(e)) return
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    if (!busy) send()
                }
            }
        })
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = grow()
            override fun removeUpdate(e: DocumentEvent) = grow()
            override fun changedUpdate(e: DocumentEvent) = grow()
        })
        agentBox.addActionListener {
            val c = agentBox.selectedItem as? Choice
            if (!populating && c != null) change("agent") { service.setMode(c.id) }
        }
        modelBox.addActionListener {
            val c = modelBox.selectedItem as? Choice
            if (!populating && c != null) change("model") { service.setModel(c.id) }
        }
        service.addListener(parent) { event ->
            ApplicationManager.getApplication().invokeLater { onEvent(event) }
        }
        // Colours are embedded in the transcript HTML, so a theme switch just needs a re-render.
        ApplicationManager.getApplication().messageBus.connect(parent)
            .subscribe(LafManagerListener.TOPIC, LafManagerListener { renderTranscript() })

        renderTranscript()
        connect()
    }

    // ---------- layout ----------

    private fun header(): JPanel {
        val actions = DefaultActionGroup().apply {
            // Far left: the session list toggle, then the other icons.
            add(object : ToggleAction("Session list", "Show or hide the session list", AllIcons.Actions.ShowAsTree), com.intellij.openapi.project.DumbAware {
                override fun isSelected(e: AnActionEvent) = sidebar?.shown == true
                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    sidebar?.shown = state
                }

                override fun update(e: AnActionEvent) {
                    super.update(e)
                    e.presentation.isVisible = sidebar != null
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
            add(action("Slash commands and skills", AllIcons.Actions.Lightning) { showCommandMenu() })
            // The sidebar has a New chat button of its own; show this one only when the sidebar is hidden.
            add(object : DumbAwareAction("New chat", "Start a new chat", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) = newChat()

                override fun update(e: AnActionEvent) {
                    e.presentation.isVisible = sidebar?.shown != true
                }

                override fun getActionUpdateThread() = ActionUpdateThread.EDT
            })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("KiroChatHeader", actions, true)
        toolbar.targetComponent = this
        headerToolbar = toolbar.component
        headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(JBColor.border()), JBUI.Borders.empty(2, 6, 4, 6))
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                add(headerToolbar, BorderLayout.WEST)
                add(planPanel, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(JPanel(java.awt.GridLayout(1, 2, JBUI.scale(4), 0)).apply {
                isOpaque = false
                add(agentBox)
                add(modelBox)
            }, BorderLayout.CENTER)
            // In a narrow window the credits drop to their short form ("30.6/50") so the row never overflows.
            addComponentListener(object : java.awt.event.ComponentAdapter() {
                override fun componentResized(e: java.awt.event.ComponentEvent) {
                    plan?.let { showPlan(it) }
                }
            })
        }
        return headerPanel
    }

    private fun composer(): JPanel {
        val box = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border()), JBUI.Borders.empty(2))
            add(JBScrollPane(input).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 4, 2)).apply { add(actionButton) }, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(6)
            add(status.apply { border = JBUI.Borders.empty(0, 2, 4, 2) }, BorderLayout.NORTH)
            add(box, BorderLayout.CENTER)
        }
    }

    private fun grow() {
        val rows = input.lineCount.coerceIn(2, 8)
        if (rows != input.rows) {
            input.rows = rows
            revalidate()
        }
    }

    private fun action(text: String, icon: javax.swing.Icon, run: () -> Unit) = object : DumbAwareAction(text, text, icon) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    // ---------- public hooks ----------

    /** Insert text (e.g. a code selection from the editor) into the input box. */
    fun appendToInput(text: String) {
        input.append(if (input.text.isEmpty() || input.text.endsWith("\n")) text else "\n$text")
        input.requestFocusInWindow()
    }

    /** Puts [text] at the start of the input (used to pick a skill from the Library tab) and focuses it. */
    fun prefillInput(text: String) {
        input.text = if (input.text.isBlank()) text else text + input.text
        input.caretPosition = input.text.length
        input.requestFocusInWindow()
    }

    /** Skills change rarely; reading the folders on every keystroke would be wasteful. */
    private fun skills(): List<SkillRef> {
        val now = System.currentTimeMillis()
        if (now - skillsLoadedAt > 5_000) {
            skillsCache = KiroResources(project.basePath?.let { Path.of(it) }, Path.of(System.getProperty("user.home")))
                .skills().map { SkillRef(it.name, it.summary) }
            skillsLoadedAt = now
        }
        return skillsCache
    }

    /**
     * Shows a stored session at once, read straight from Kiro's files, and in the background reloads it so you can
     * continue it. Sessions Kiro can't reload (the v3 store) stay on screen as read-only.
     */
    fun openStored(session: KiroSession) {
        val gen = ++generation
        reset()
        displayedId = session.id
        viewOnly = !session.resumable
        opening = session.resumable
        replayCovered = false
        setTransient(if (session.resumable) "Opening session…" else null)
        updateComposer()
        onSessionsChanged?.invoke() // moves the highlight in the sidebar

        ApplicationManager.getApplication().executeOnPooledThread {
            val items = TranscriptReader.read(session)
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                blocks.clear() // also drops anything the replay managed to add first, so nothing shows twice
                if (items.isNotEmpty()) {
                    replayCovered = true
                    addPreview(items)
                }
                if (viewOnly) {
                    blocks.add(ChatBlock.Note("View only — the installed kiro-cli can't reopen this kind of session, so you can read it here but not continue it. Start a new chat to ask Kiro something."))
                }
                renderTranscript()
            }
        }
        if (!session.resumable) return

        val dir = session.cwd?.takeIf { java.io.File(it).isDirectory } ?: project.basePath ?: System.getProperty("user.home")
        service.resume(session.id, dir).whenComplete { info, error ->
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                opening = false
                replayCovered = false
                setTransient(null)
                if (error != null) {
                    viewOnly = true
                    notice("Could not continue this session: ${error.cause?.message ?: error.message}")
                } else {
                    populate(info)
                }
                updateComposer()
                onSessionsChanged?.invoke()
            }
        }
    }

    private fun addPreview(items: List<PreviewItem>) {
        items.forEachIndexed { i, item ->
            blocks.add(
                when (item) {
                    is PreviewItem.User -> ChatBlock.User(item.text)
                    is PreviewItem.Assistant -> ChatBlock.Agent(StringBuilder(item.text))
                    is PreviewItem.Tool -> ChatBlock.Tool("preview-$i", item.title, "completed")
                },
            )
        }
    }

    // ---------- session / connection ----------

    /** First connection when the panel opens. Sending is allowed even if this fails, which retries the start. */
    private fun connect() {
        val gen = generation
        setTransient("Connecting to Kiro…")
        opening = true
        updateComposer()
        service.start().whenComplete { info, error ->
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                opening = false
                if (error != null) {
                    setTransient("Cannot start kiro-cli: ${error.cause?.message ?: error.message} — check Settings → Tools → Kiro")
                } else {
                    setTransient(null)
                    populate(info)
                    onSessionsChanged?.invoke()
                }
                updateComposer()
            }
        }
    }

    private fun populate(info: SessionInfo) {
        populating = true
        try {
            fill(agentBox, info.modes, info.currentModeId)
            fill(modelBox, info.models, info.currentModelId)
        } finally {
            populating = false
        }
        refreshPlanUsage()
    }

    /** Asks Kiro for the plan's credits. Quiet on failure: the previous figure (or nothing) stays. */
    private fun refreshPlanUsage(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastUsageFetch < USAGE_MIN_INTERVAL_MS) return
        lastUsageFetch = now
        service.usage().whenComplete { usage, error ->
            if (error == null && usage != null) ApplicationManager.getApplication().invokeLater { showPlan(usage) }
        }
    }

    private fun showPlan(u: PlanUsage) {
        plan = u
        val narrow = headerPanel.width in 1..HEADER_NARROW_PX
        val color = when (u.level) {
            UsageLevel.OK -> UIUtil.getContextHelpForeground()
            UsageLevel.WARNING -> JBColor(0xC77700, 0xF2A33A)
            UsageLevel.CRITICAL -> JBColor(0xC62828, 0xFF6B68)
        }
        planBar.fraction = u.fraction
        planBar.fill = if (u.level == UsageLevel.OK) JBUI.CurrentTheme.Link.Foreground.ENABLED else color
        planBar.isVisible = u.limit != null
        planLabel.text = if (narrow) u.compact else u.summary
        planLabel.foreground = color
        planPanel.toolTipText = u.tooltip()
        planLabel.toolTipText = u.tooltip()
        planBar.toolTipText = u.tooltip()
        planPanel.isVisible = true
    }

    private fun fill(box: ComboBox<Choice>, items: List<Choice>, currentId: String?) {
        box.model = DefaultComboBoxModel(items.toTypedArray())
        box.selectedItem = items.firstOrNull { it.id == currentId }
        box.isEnabled = items.isNotEmpty()
    }

    private fun change(what: String, action: () -> java.util.concurrent.CompletableFuture<Unit>) {
        action().whenComplete { _, error ->
            if (error != null) ApplicationManager.getApplication().invokeLater {
                notice("Could not change $what: ${error.cause?.message ?: error.message}")
            }
        }
    }

    private fun send() {
        val text = input.text.trim()
        if (text.isEmpty() || busy || opening || viewOnly) return
        input.text = ""
        blocks.add(ChatBlock.User(text))
        scheduleRender()
        setBusy(true)
        val gen = generation
        service.prompt(text).whenComplete { reason, error ->
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater // the conversation was replaced meanwhile
                if (error != null) notice("Error: ${error.cause?.message ?: error.message}")
                else if (reason != "end_turn") notice("Stopped: $reason")
                setBusy(false)
                onSessionsChanged?.invoke() // the session now has a first prompt, and a newer timestamp
                if (error == null) refreshPlanUsage(force = true) // the turn just spent credits
            }
        }
    }

    /** A fresh session on the already-running Kiro process (about a second, not a restart). */
    fun newChat() {
        val gen = ++generation
        reset()
        displayedId = null
        viewOnly = false
        opening = true
        replayCovered = false
        setTransient("Starting a new chat…")
        updateComposer()
        service.newSession().whenComplete { info, error ->
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                opening = false
                if (error != null) {
                    setTransient("Cannot start kiro-cli: ${error.cause?.message ?: error.message} — check Settings → Tools → Kiro")
                } else {
                    setTransient(null)
                    populate(info)
                }
                updateComposer()
                onSessionsChanged?.invoke()
            }
        }
        onSessionsChanged?.invoke()
    }

    private fun reset() {
        blocks.clear()
        credits = 0.0
        usageText = ""
        setBusy(false)
        renderTranscript()
    }

    // ---------- state shown to the user ----------

    private fun setBusy(value: Boolean) {
        busy = value
        updateComposer()
    }

    /** One place decides what the button, the hint inside the input and the status line say. */
    private fun updateComposer() {
        actionButton.text = if (busy) "Stop" else "Send"
        actionButton.icon = if (busy) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
        actionButton.isEnabled = busy || !(opening || viewOnly)
        input.emptyText.text = when {
            viewOnly -> "View only — start a new chat to ask Kiro something"
            opening -> "Opening…   you can already type"
            else -> DEFAULT_HINT
        }
        input.repaint()
        updateStatus()
    }

    private fun setTransient(message: String?) {
        transient = message
        updateStatus()
    }

    private fun updateStatus() {
        val message = transient ?: if (busy) "Kiro is working…" else null
        status.icon = if (message != null && (busy || opening)) AnimatedIcon.Default() else null
        status.text = message ?: usageText.ifEmpty { " " }
        status.foreground = if (message != null) UIUtil.getLabelForeground() else UIUtil.getContextHelpForeground()
    }

    private fun onEvent(event: AcpEvent) {
        if (opening && replayCovered) {
            when (event) {
                is AcpEvent.AgentText, is AcpEvent.UserText, is AcpEvent.ToolCall, is AcpEvent.ToolCallUpdate, is AcpEvent.Plan -> return
                else -> Unit
            }
        }
        when (event) {
            is AcpEvent.AgentText -> {
                (blocks.lastOrNull() as? ChatBlock.Agent ?: ChatBlock.Agent().also { blocks.add(it) }).text.append(event.text)
                scheduleRender()
            }
            is AcpEvent.UserText -> if (event.replay) {
                val last = blocks.lastOrNull()
                if (last is ChatBlock.User) blocks[blocks.lastIndex] = ChatBlock.User(last.text + event.text) else blocks.add(ChatBlock.User(event.text))
                scheduleRender()
            }
            is AcpEvent.ToolCall -> {
                blocks.add(ChatBlock.Tool(event.id, event.title, event.status))
                scheduleRender()
            }
            is AcpEvent.ToolCallUpdate -> {
                blocks.lastOrNull { it is ChatBlock.Tool && it.id == event.id }?.let { tool ->
                    tool as ChatBlock.Tool
                    event.title?.let { tool.title = it }
                    event.status?.let { tool.status = it }
                    scheduleRender()
                }
            }
            is AcpEvent.Plan -> notice("Plan:\n" + event.entries.joinToString("\n") { "• $it" })
            is AcpEvent.Commands -> commands = event.commands
            is AcpEvent.Metadata -> {
                event.credits?.let { credits += it }
                val parts = listOfNotNull(
                    event.contextPercent?.let { "Context %.0f%%".format(it) },
                    if (credits > 0) "%.2f credits".format(credits) else null,
                )
                if (parts.isNotEmpty()) {
                    usageText = parts.joinToString("  ·  ")
                    updateStatus()
                }
            }
            is AcpEvent.Closed -> {
                notice("kiro-cli exited${event.reason?.let { ": $it" } ?: ""}")
                opening = false
                setBusy(false)
            }
            is AcpEvent.AgentThought, is AcpEvent.Other -> Unit
        }
    }

    // ---------- menus and clipboard ----------

    /** Slash commands advertised by Kiro plus the skills found on disk; picking one fills the input box. */
    private fun showCommandMenu() {
        val resources = KiroResources(project.basePath?.let { Path.of(it) }, Path.of(System.getProperty("user.home")))
        val entries = commands.map { it.name to "${it.name} — ${it.description}" } +
            resources.skills().map { "/${it.name}" to "/${it.name} — skill (${it.scope.label}): ${it.summary}" }
        if (entries.isEmpty()) {
            notice("No slash commands yet — Kiro sends them once the session is ready.")
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(entries)
            .setRenderer(SimpleListCellRenderer.create("") { it.second })
            .setNamerForFiltering { it.second }
            .setItemChosenCallback { chosen ->
                input.text = chosen.first + " "
                input.requestFocusInWindow()
            }
            .createPopup()
            .showUnderneathOf(headerToolbar)
    }

    private fun onLink(e: HyperlinkEvent) {
        val href = e.description.orEmpty()
        val file = MarkdownHtml.parseFileLink(href)
        when (e.eventType) {
            HyperlinkEvent.EventType.ENTERED -> transcript.toolTipText = file?.let { "Ctrl+click to open ${it.target}" + (it.line?.let { l -> ":$l" } ?: "") }
            HyperlinkEvent.EventType.EXITED -> transcript.toolTipText = null
            HyperlinkEvent.EventType.ACTIVATED -> when {
                file != null -> {
                    val click = e.inputEvent as? java.awt.event.MouseEvent
                    if (click != null && (click.isControlDown || click.isMetaDown)) openFileRef(file)
                    else flash("Hold Ctrl and click to open ${file.target}")
                }
                href.startsWith("copycode:") -> copyCode(href)
                href.startsWith("copy:") -> copyBlock(href.removePrefix("copy:").toIntOrNull())
                else -> e.url?.let { BrowserUtil.browse(it.toString()) }
            }
            else -> Unit
        }
    }

    private fun openFileRef(ref: MarkdownHtml.FileRef) {
        val path = if (FileResolver.isAbsolute(ref.target)) ref.target else project.basePath?.trimEnd('/', '\\') + "/" + ref.target
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByPath(path.replace('\\', '/'))
        if (vf == null) {
            flash("File not found: ${ref.target}")
            return
        }
        OpenFileDescriptor(project, vf, ((ref.line ?: 1) - 1).coerceAtLeast(0), 0).navigate(true)
    }

    private fun copyCode(href: String) {
        val parts = href.removePrefix("copycode:").split(':')
        val block = parts.getOrNull(0)?.toIntOrNull()?.let { blocks.getOrNull(it) } as? ChatBlock.Agent ?: return
        val code = parts.getOrNull(1)?.toIntOrNull()?.let { MarkdownHtml.codeBlocks(block.text.toString()).getOrNull(it) } ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(code))
        flash("Code copied")
    }

    /** A short message in the status line that clears itself. */
    private fun flash(message: String) {
        setTransient(message)
        Timer(1800) { if (transient == message) setTransient(null) }.apply { isRepeats = false; start() }
    }

    private fun copyBlock(index: Int?) {
        val block = index?.let { blocks.getOrNull(it) } as? ChatBlock.Agent ?: return
        CopyPasteManager.getInstance().setContents(StringSelection(block.text.toString()))
        flash("Copied")
    }

    // ---------- transcript ----------

    private fun notice(text: String) {
        blocks.add(ChatBlock.Note(text))
        scheduleRender()
    }

    private fun scheduleRender() {
        if (!renderTimer.isRunning) renderTimer.start()
    }

    private fun palette(): Palette {
        val fg = UIUtil.getLabelForeground()
        val bg = UIUtil.getTextFieldBackground()
        fun hex(c: Color) = ColorUtil.toHex(c)
        return Palette(
            fg = hex(fg),
            muted = hex(UIUtil.getContextHelpForeground()),
            card = hex(ColorUtil.mix(bg, fg, 0.07)),
            code = hex(ColorUtil.mix(bg, fg, 0.11)),
            accent = hex(JBUI.CurrentTheme.Link.Foreground.ENABLED),
            ok = hex(JBColor(0x2E7D32, 0x62B543)),
            err = hex(JBColor(0xC62828, 0xFF6B68)),
            syntax = if (JBColor.isBright()) SyntaxColors.LIGHT else SyntaxColors.DARK,
        )
    }

    /** Re-renders the whole transcript; keeps the user's scroll position unless they were following the tail. */
    private fun renderTranscript() {
        val bar = scroll.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 8
        val previous = bar.value
        transcript.background = UIUtil.getTextFieldBackground()
        transcript.text = TranscriptHtml.render(blocks, palette(), resolver, files.generation)
        SwingUtilities.invokeLater { bar.value = if (atBottom) bar.maximum else previous }
    }

    private fun choiceRenderer(label: String) = SimpleListCellRenderer.create<Choice>(label) { it.name }

    private companion object {
        const val USAGE_MIN_INTERVAL_MS = 10_000L
        const val HEADER_NARROW_PX = 340
        const val DEFAULT_HINT = "Ask Kiro…   / commands · @ files and skills · Shift+Enter new line"
    }
}
