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
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.ColorUtil
import com.intellij.ui.JBColor
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.RoundedLineBorder
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kirogui.jetbrains.acp.AcpEvent
import com.kirogui.jetbrains.acp.Choice
import com.kirogui.jetbrains.acp.PlanUsage
import com.kirogui.jetbrains.acp.PromptImage
import com.kirogui.jetbrains.acp.SessionInfo
import com.kirogui.jetbrains.acp.UsageLevel
import com.kirogui.jetbrains.acp.SlashCommand
import com.kirogui.jetbrains.jobs.JobService
import com.kirogui.jetbrains.jobs.Notice
import com.kirogui.jetbrains.jobs.ToolGuidance
import com.kirogui.jetbrains.jobs.WakePrompt
import com.kirogui.jetbrains.kiro.CompletionEngine
import com.kirogui.jetbrains.kiro.LocalCommands
import com.kirogui.jetbrains.kiro.FileResolver
import com.kirogui.jetbrains.kiro.KiroSession
import com.kirogui.jetbrains.kiro.PreviewItem
import com.kirogui.jetbrains.kiro.TranscriptReader
import com.kirogui.jetbrains.kiro.KiroResources
import com.kirogui.jetbrains.kiro.SkillRef
import com.kirogui.jetbrains.service.KiroAcpService
import com.kirogui.jetbrains.settings.KiroSettings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.nio.file.Path
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
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

    private val service = project.getService(KiroAcpService::class.java).conversation(parent)
    private val jobs = project.getService(JobService::class.java)
    private val blocks = mutableListOf<ChatBlock>()

    // The chat is drawn in an embedded browser (rounded cards, timeline) when the IDE has one; see Settings > Kiro.
    private val view: TranscriptView = TranscriptView.create(parent, KiroSettings.getInstance().state.webChat)

    private val input = object : JBTextArea(2, 40) {
        /** A long paste becomes a chip ("Pasted text - 120 lines") instead of flooding the input box. */
        override fun paste() {
            val text = try {
                CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor)
            } catch (_: Exception) {
                null
            }
            when {
                text != null && Attachments.isLongPaste(text) -> attach(Attachment.Snippet(text.replace("\r\n", "\n")))
                text != null -> super.paste()
                !pasteNonText() -> super.paste()
            }
        }
    }.apply {
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(4, 6)
        emptyText.text = DEFAULT_HINT
    }
    private val attachments = mutableListOf<Attachment>()
    private var openFile: String? = null // the file in the selected editor tab, as a project-relative path
    private var openFileSkipped: String? = null // the user clicked x on its chip; applies until the tab changes
    private val attachmentsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
    }
    private val attachButton = iconButton(
        AllIcons.General.Add,
        "Attach a file, the selected code or an image. Typing @ also picks a file; Ctrl+V pastes screenshots.",
    ) { showAttachMenu() }
    private val slashButton = iconButton(AllIcons.Actions.Lightning, "Slash commands and skills") { showCommandMenu() }
    private val actionButton = SendButton { if (busy) service.cancel() else send() }
    private val retryButton = JButton("Retry", AllIcons.Actions.Refresh).apply {
        isVisible = false
        toolTipText = "Restart kiro-cli and reconnect"
    }
    private val status = JBLabel(" ").apply {
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (statusShowsJobs) onShowJobs?.invoke()
            }
        })
    }
    private var statusShowsJobs = false

    /** Opens the Jobs tab; set by the tool window. */
    var onShowJobs: (() -> Unit)? = null

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

    private val agentBox = ComboBox<Choice>().apply {
        renderer = choiceRenderer("Agent")
        toolTipText = "Agent"
        prototypeDisplayValue = Choice("m", "kiro_default_agent", null)
    }
    private val modelBox = ComboBox<Choice>().apply {
        renderer = choiceRenderer("Model")
        toolTipText = "Model"
        prototypeDisplayValue = Choice("m", "claude-sonnet-4.5", null)
    }
    private val effortBox = ComboBox<Choice>().apply {
        renderer = choiceRenderer("Effort")
        toolTipText = "Effort"
        prototypeDisplayValue = Choice("m", "medium", null)
        isVisible = false // only when kiro-cli offers it
    }
    private var plan: PlanUsage? = null

    private var commands: List<SlashCommand> = emptyList()
    private var populating = false
    private var busy = false
    private var transient: String? = null
    private var usageText = ""
    private var credits = 0.0
    private var lastDisplayError: String? = null // Kiro repeats the same warning; show it once
    /** The next prompt carries a short reminder about the IDE tools (it costs no extra turn): a new conversation, or Kiro just summarised the chat. */
    private var remindTools = true
    private var holdWake = false // the user just pressed Stop: no job news for Kiro until they speak again

    // Which conversation is on screen. Callbacks from earlier requests compare `generation` and drop themselves.
    private var generation = 0
    private var opening = false // a session is being created or reloaded; sending now would reach the wrong one
    private var viewOnly = false // a stored session Kiro can't continue is on screen
    private var replayCovered = false // history already shown from the files, so the agent's replay is redundant
    private var displayedId: String? = null
    private var failed = false // kiro-cli didn't start or died; the Retry button is showing
    private var lastSessionId: String? = null // so a reconnect can pick the conversation up again
    private var lastCwd: String? = null

    /** Called whenever the set of stored sessions probably changed (a turn finished, a session was created or opened). */
    var onSessionsChanged: (() -> Unit)? = null

    val isBusy: Boolean get() = busy

    /** The session on screen: the one being viewed, else the live one. */
    fun currentSessionId(): String? = displayedId ?: service.currentSessionId()

    /** The first thing the user asked in this chat (for the tab's name), or null while it is empty. */
    fun firstPrompt(): String? = (blocks.firstOrNull { it is ChatBlock.User } as? ChatBlock.User)?.text?.trim()?.takeIf { it.isNotEmpty() }

    /** Nothing said yet in this chat, and nothing under way: a "new chat" can reuse it. */
    val isEmptyChat: Boolean get() = blocks.none { it is ChatBlock.User } && !busy && !opening && !viewOnly

    /** The agent this chat runs as, or null before a session exists. */
    fun currentAgentId(): String? = service.currentAgentId()

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
        CompletionEngine(commands = { localCommands() + commands }, skills = { skills() }, files = { files.get() }),
        parent,
    )

    private val renderTimer = Timer(60) { renderTranscript() }.apply { isRepeats = false }

    init {
        view.onLink = ::handleLink
        add(view.component, BorderLayout.CENTER)
        add(composer(), BorderLayout.SOUTH)

        completion.onFile = { path -> attach(Attachment.File(path)) }
        retryButton.addActionListener { reconnect() }
        addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) {
                plan?.let { showPlan(it) }
            }
        })
        applyFont()
        planPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) = refreshPlanUsage(force = true)
        })
        completion.install()
        files.get() // start scanning now so the first "@" already has results
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (completion.handleKey(e)) return
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    e.consume()
                    // Swing ignores Shift+Enter in a text area, so the line break is inserted by hand.
                    if (e.isShiftDown || e.isControlDown || e.isAltDown || e.isMetaDown) input.replaceSelection("\n")
                    else if (!busy) send()
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
        effortBox.addActionListener {
            val c = effortBox.selectedItem as? Choice
            if (!populating && c != null) change("effort") { service.setEffort(c.id) }
        }
        // Typing in an open list jumps to / filters the matching agent, model or effort.
        for (box in listOf(agentBox, modelBox, effortBox)) com.intellij.ui.ComboboxSpeedSearch.installOn(box)
        service.addListener { event ->
            ApplicationManager.getApplication().invokeLater { onEvent(event) }
        }
        jobs.addListener(parent) { notice -> ApplicationManager.getApplication().invokeLater { onJobs(notice) } }
        // Colours are embedded in the transcript HTML, so a theme switch just needs a re-render.
        val bus = ApplicationManager.getApplication().messageBus.connect(parent)
        bus.subscribe(LafManagerListener.TOPIC, LafManagerListener { applyFont(); renderTranscript() })
        bus.subscribe(KiroSettings.TOPIC, KiroSettings.Listener { applyFont(); renderTranscript(); refreshAttachments() })
        project.messageBus.connect(parent).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun selectionChanged(event: FileEditorManagerEvent) = ApplicationManager.getApplication().invokeLater { updateOpenFile() }
                override fun fileClosed(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) = ApplicationManager.getApplication().invokeLater { updateOpenFile() }
            },
        )
        updateOpenFile()

        renderTranscript()
        connect()
    }

    // ---------- layout ----------

    private fun composer(): JPanel {
        val box = JPanel(BorderLayout()).apply {
            // A softly rounded outline in the accent colour reads friendlier than a hard rectangle.
            border = JBUI.Borders.compound(RoundedLineBorder(ACCENT, JBUI.scale(12), 1), JBUI.Borders.empty(6, 8, 2, 6))
            add(attachmentsPanel, BorderLayout.NORTH)
            add(JBScrollPane(input).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyTop(2)
                // Agent and model are compact chips beside the buttons, not full-width form fields.
                add(JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                    isOpaque = false
                    add(attachButton)
                    add(slashButton)
                    add(agentBox)
                    add(modelBox)
                    add(effortBox)
                }, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply { isOpaque = false; add(actionButton) }, BorderLayout.EAST)
            }, BorderLayout.SOUTH)
        }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
            add(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
                border = JBUI.Borders.empty(0, 2, 6, 2)
                add(status, BorderLayout.CENTER)
                add(JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
                    isOpaque = false
                    add(planPanel)
                    add(retryButton)
                }, BorderLayout.EAST)
            }, BorderLayout.NORTH)
            add(box, BorderLayout.CENTER)
        }
    }

    private fun grow() {
        updateComposer()
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

    /** A picture or files on the clipboard (a screenshot, files copied in Explorer) become chips. False if neither. */
    private fun pasteNonText(): Boolean {
        val copy = CopyPasteManager.getInstance()
        if (copy.areDataFlavorsAvailable(java.awt.datatransfer.DataFlavor.imageFlavor)) {
            val image = try {
                copy.getContents<java.awt.Image>(java.awt.datatransfer.DataFlavor.imageFlavor)
            } catch (_: Exception) {
                null
            }
            val item = image?.let { ImageAttachments.fromImage(it) }
            if (item != null) {
                attach(item)
                return true
            }
        }
        if (copy.areDataFlavorsAvailable(java.awt.datatransfer.DataFlavor.javaFileListFlavor)) {
            val list = try {
                copy.getContents<List<java.io.File>>(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
            } catch (_: Exception) {
                null
            }
            if (!list.isNullOrEmpty()) {
                list.filter { it.isFile }.forEach { attachIoFile(it) }
                return true
            }
        }
        return false
    }

    /** An image file is attached as a picture, anything else by name (the agent reads it). */
    private fun attachIoFile(file: java.io.File) {
        if (ImageAttachments.isImageFile(file.name)) {
            val image = ImageAttachments.fromFile(file)
            if (image != null) return attach(image) else flash("Could not read ${file.name} as an image")
        }
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
        attach(if (vf != null) AttachmentSources.file(project, vf) else Attachment.File(file.path.replace('\\', '/')))
    }

    /** Adds a chip above the input; the attachment travels with the next prompt. */
    fun attach(item: Attachment) {
        if (item in attachments) return
        attachments.add(item)
        refreshAttachments()
        input.requestFocusInWindow()
    }

    /** Follows the selected editor tab; its file rides along with each prompt unless the user skipped it. */
    private fun updateOpenFile() {
        val vf = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.takeIf { it.isInLocalFileSystem && !it.isDirectory }
        val path = vf?.let { AttachmentSources.path(project, it) }
        if (path == openFile) return
        openFile = path
        openFileSkipped = null
        refreshAttachments()
    }

    private fun autoAttachment(): Attachment.File? =
        openFile?.takeIf { KiroSettings.getInstance().state.autoAttachOpenFile && it != openFileSkipped }
            ?.let { Attachment.File(it) }?.takeIf { it !in attachments }

    private fun refreshAttachments() {
        attachmentsPanel.removeAll()
        autoAttachment()?.let { attachmentsPanel.add(chip(it, auto = true)) }
        attachments.forEach { a -> attachmentsPanel.add(chip(a)) }
        attachmentsPanel.isVisible = attachmentsPanel.componentCount > 0
        updateComposer()
        attachmentsPanel.revalidate()
        attachmentsPanel.repaint()
        revalidate()
    }

    /** One removable row: icon, name (click to look at it), and an x. */
    private fun chip(a: Attachment, auto: Boolean = false): JComponent {
        val icon = when {
            a is Attachment.File -> FileTypeManager.getInstance().getFileTypeByFileName(a.label).icon
            a is Attachment.Image -> AllIcons.FileTypes.Image
            a is Attachment.Snippet && a.source == null -> AllIcons.FileTypes.Text
            else -> AllIcons.Actions.Copy
        }
        val label = JBLabel(if (auto) a.label + "  ·  open file" else a.label, icon, javax.swing.SwingConstants.LEFT).apply {
            toolTipText = if (auto) "The file open in the editor is attached automatically. Click x to skip it." else if (a is Attachment.File) a.path else "Click to see the whole snippet"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) = previewAttachment(a)
            })
        }
        val remove = JBLabel(AllIcons.Actions.Close).apply {
            toolTipText = "Remove"
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    if (auto) openFileSkipped = openFile else attachments.remove(a)
                    refreshAttachments()
                }
            })
        }
        val body = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(RoundedLineBorder(JBColor.border(), JBUI.scale(10), 1), JBUI.Borders.empty(1, 8))
            add(label, BorderLayout.CENTER)
            add(remove, BorderLayout.EAST)
        }
        return JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(2))).apply { isOpaque = false; add(body) }
    }

    private fun previewAttachment(a: Attachment) {
        when (a) {
            is Attachment.File -> openFileRef(MarkdownHtml.FileRef(a.path, null))
            is Attachment.Image -> {
                val icon = ImageAttachments.icon(a, JBUI.scale(640), JBUI.scale(480)) ?: return
                val pane = JBScrollPane(JBLabel(icon)).apply { border = JBUI.Borders.empty() }
                JBPopupFactory.getInstance().createComponentPopupBuilder(pane, null)
                    .setTitle(a.label).setResizable(true).setMovable(true).setRequestFocus(false)
                    .createPopup().showInCenterOf(this)
            }
            is Attachment.Snippet -> {
                val area = JBTextArea(a.code).apply { isEditable = false; caretPosition = 0 }
                val pane = JBScrollPane(area).apply { preferredSize = java.awt.Dimension(JBUI.scale(560), JBUI.scale(300)) }
                JBPopupFactory.getInstance().createComponentPopupBuilder(pane, area)
                    .setTitle(a.label).setResizable(true).setMovable(true).setRequestFocus(true)
                    .createPopup().showInCenterOf(this)
            }
        }
    }

    private class AttachChoice(val label: String, val icon: javax.swing.Icon?, val run: () -> Unit)

    /** What can be attached right now: the selection, the open file, other open tabs, or any file. */
    private fun showAttachMenu() {
        val fem = FileEditorManager.getInstance(project)
        val editor = fem.selectedTextEditor
        val current = fem.selectedFiles.firstOrNull()
        val types = FileTypeManager.getInstance()
        val entries = ArrayList<AttachChoice>()
        if (editor != null) {
            AttachmentSources.selection(project, editor, current)?.let { snippet ->
                entries.add(AttachChoice("Selected code - ${snippet.label}", AllIcons.Actions.Copy) { attach(snippet) })
            }
        }
        if (current != null) {
            entries.add(AttachChoice("Current file - ${AttachmentSources.path(project, current)}", types.getFileTypeByFileName(current.name).icon) {
                attach(AttachmentSources.file(project, current))
            })
        }
        fem.openFiles.filter { it != current }.forEach { vf ->
            entries.add(AttachChoice(AttachmentSources.path(project, vf), types.getFileTypeByFileName(vf.name).icon) {
                attach(AttachmentSources.file(project, vf))
            })
        }
        entries.add(AttachChoice("Choose a file...", AllIcons.Nodes.Folder) {
            FileChooser.chooseFile(FileChooserDescriptorFactory.singleFile(), project, null) { vf ->
                attachIoFile(java.io.File(vf.path))
            }
        })
        JBPopupFactory.getInstance().createPopupChooserBuilder(entries)
            .setTitle("Attach")
            .setRenderer(listRenderer<AttachChoice> { label, value -> value?.let { label.append(it.label); label.icon = it.icon } })
            .setItemChosenCallback { it.run() }
            .createPopup()
            .showUnderneathOf(attachButton)
    }

    /** Puts the cursor in the input box. */
    fun requestInputFocus() {
        input.requestFocusInWindow()
    }

    /** Puts [text] at the start of the input (used to pick a skill from the Library) and focuses it. */
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
     * continue it. A session the running engine can't reload (a v3-store session on engine v2) stays as read-only.
     */
    fun openStored(session: KiroSession) {
        val gen = ++generation
        reset()
        displayedId = session.id
        viewOnly = false
        opening = true
        replayCovered = false
        setTransient("Opening session…")
        updateComposer()
        onSessionsChanged?.invoke() // moves the highlight in the session list and updates the tab name

        ApplicationManager.getApplication().executeOnPooledThread {
            val items = TranscriptReader.read(session)
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                blocks.clear() // also drops anything the replay managed to add first, so nothing shows twice
                if (items.isNotEmpty()) {
                    replayCovered = true
                    addPreview(items)
                }
                renderTranscript()
            }
        }
        val dir = session.cwd?.takeIf { java.io.File(it).isDirectory } ?: project.basePath ?: System.getProperty("user.home")
        lastCwd = dir
        service.resume(session.id, dir).whenComplete { info, error ->
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                opening = false
                replayCovered = false
                setTransient(null)
                if (error != null) {
                    viewOnly = true
                    val why = error.cause?.message ?: error.message ?: ""
                    notice(
                        if (session.needsV3 && "not found" in why.lowercase()) {
                            "View only: this session was made by kiro-cli's v3 engine and the v2 engine can't reopen it. Set Agent engine to v3 (Settings → Tools → Kiro) to continue it, or start a new chat."
                        } else {
                            "Could not continue this session: $why"
                        },
                    )
                } else {
                    populate(info)
                }
                updateComposer()
                onSessionsChanged?.invoke()
                deliverNotices()
            }
        }
    }

    private fun addPreview(items: List<PreviewItem>) {
        items.forEachIndexed { i, item ->
            blocks.add(
                when (item) {
                    is PreviewItem.User -> ChatBlock.User(item.text, time = 0)
                    is PreviewItem.Assistant -> ChatBlock.Agent(StringBuilder(item.text), time = 0)
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
                    fail("Cannot start kiro-cli: ${error.cause?.message ?: error.message} — check Settings → Tools → Kiro")
                } else {
                    setFailed(false)
                    setTransient(null)
                    populate(info)
                    onSessionsChanged?.invoke()
                }
                updateComposer()
                deliverNotices()
            }
        }
    }

    private fun populate(info: SessionInfo) {
        lastSessionId = info.sessionId
        populating = true
        try {
            fill(agentBox, info.modes, info.currentModeId)
            fill(modelBox, info.models, info.currentModelId)
            fill(effortBox, info.efforts, info.currentEffortId)
            effortBox.isVisible = info.efforts.isNotEmpty()
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

    /** Secondary text that is still comfortable to read: the label colour, a little softened. */
    private fun readable(): java.awt.Color = ColorUtil.mix(UIUtil.getPanelBackground(), UIUtil.getLabelForeground(), 0.78)

    private fun showPlan(u: PlanUsage) {
        plan = u
        val narrow = width in 1..HEADER_NARROW_PX
        val color = when (u.level) {
            UsageLevel.OK -> JBUI.CurrentTheme.Link.Foreground.ENABLED
            UsageLevel.WARNING -> JBColor(0xC77700, 0xF2A33A)
            UsageLevel.CRITICAL -> JBColor(0xD9534F, 0xE5736F)
        }
        planBar.fraction = u.fraction
        planBar.fill = color
        planBar.isVisible = u.limit != null
        planLabel.text = if (narrow) u.compact else u.summary
        planLabel.foreground = readable() // the bar carries the level; the numbers stay easy to read
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
        val sent = listOfNotNull(autoAttachment()) + attachments
        if ((text.isEmpty() && attachments.isEmpty()) || busy || opening || viewOnly) return
        if (attachments.isEmpty()) LocalCommands.parse(text, commands)?.let { call ->
            input.text = ""
            runLocal(call)
            return
        }
        input.text = ""
        attachments.clear()
        refreshAttachments()
        holdWake = false
        val pictures = sent.filterIsInstance<Attachment.Image>().map { PromptImage(it.mime, it.bytes) }
        submit(ChatBlock.User(text, sent), Attachments.compose(text, sent), pictures)
        view.scrollToEnd() // the user was reading further up: jump back to the end to follow the answer
    }

    /** The commands the chat runs itself; /model and /agent offer the names of what the lists hold as their arguments. */
    private fun localCommands() = LocalCommands.available(commands).map {
        when (it.name) {
            "/model" -> it.copy(subcommands = choiceNames(modelBox))
            "/agent" -> it.copy(subcommands = choiceNames(agentBox))
            else -> it
        }
    }

    private fun choiceNames(box: ComboBox<Choice>): List<String> = (0 until box.itemCount).map { box.getItemAt(it).id }

    /** Selects the agent / model that [query] names; several matches or none are said in the chat; no name lists the choices. */
    private fun choose(box: ComboBox<Choice>, what: String, query: String) {
        if (box.itemCount == 0) return notice("There is no $what to choose from yet.")
        val items = (0 until box.itemCount).map { box.getItemAt(it) }
        if (query.isBlank()) {
            val now = (box.selectedItem as? Choice)?.name
            val list = items.joinToString("\n") { "• ${it.name}" }
            return notice("${what.replaceFirstChar { it.uppercase() }}s (now: ${now ?: "none"}):\n$list\nChoose with /$what <name>.")
        }
        val hits = LocalCommands.match(items, query, { it.id }, { it.name })
        when {
            hits.isEmpty() -> notice("No $what matches \"$query\".")
            hits.size == 1 -> {
                box.selectedItem = hits.single()
                notice("${what.replaceFirstChar { it.uppercase() }}: ${hits.single().name}")
            }
            else -> notice("Several ${what}s match \"$query\": ${hits.take(8).joinToString(", ") { it.name }}. Type more of the name.")
        }
    }

    /** The slash commands the chat runs itself (see [LocalCommands]). */
    private fun runLocal(call: LocalCommands.Call) {
        when (call.name) {
            "/compact" -> compactNow()
            "/new" -> newChat()
            "/model" -> choose(modelBox, "model", call.arg)
            "/agent" -> choose(agentBox, "agent", call.arg)
            "/trust" -> when (call.arg.lowercase()) {
                "on" -> {
                    project.getService(KiroAcpService::class.java).trustAll = true
                    notice("Trust all tools is on: permission requests are approved without asking (also file edits, with no diff review). /trust off to stop.")
                }
                "off" -> {
                    project.getService(KiroAcpService::class.java).trustAll = false
                    notice(if (KiroSettings.getInstance().state.trustAllTools) "Off for this session, but Settings → Kiro still has \"Trust all tools\" on." else "Trust all tools is off.")
                }
                else -> notice("Use /trust on or /trust off.")
            }
            "/autopilot" -> {
                val v = call.arg.lowercase()
                if (v != "on" && v != "off") notice("Use /autopilot on or /autopilot off.")
                else change("autopilot") { service.setConfig("autopilot", v) }.also { notice("Autopilot $v") }
            }
            "/tools" -> notice(jobs.toolList().ifBlank { "No IDE tools are on (Settings → Tools → Kiro)." })
            "/usage" -> {
                refreshPlanUsage(force = true)
                notice(listOfNotNull(plan?.summary, usageText.takeIf { it.isNotBlank() }).joinToString("\n").ifBlank { "No usage figures yet." })
            }
            "/help" -> notice("Commands the chat runs itself:\n" + localCommands().joinToString("\n") { "${it.name} — ${it.description}" })
        }
    }

    /** Kiro's `/compact`: summarise the conversation to free context. Typing it as a prompt would only be chat text. */
    private fun compactNow() {
        notice("Compacting the conversation…")
        setBusy(true)
        val gen = generation
        service.compact().whenComplete { _, error ->
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                setBusy(false)
                if (error != null) notice("Could not compact: ${error.cause?.message ?: error.message}")
                else remindTools = true
                deliverNotices()
            }
        }
    }

    /**
     * Sends one turn. Every prompt goes through here, on the EDT, while nothing else is running: kiro-cli cancels a
     * turn when a second prompt arrives, so the user's messages and the IDE's job news must never overlap.
     */
    private fun submit(shown: ChatBlock, prompt: String, pictures: List<PromptImage>) {
        blocks.add(shown)
        scheduleRender()
        setBusy(true)
        val gen = generation
        val reminded = remindTools && KiroSettings.getInstance().state.ideTools
        if (reminded) remindTools = false
        val text = if (reminded) ToolGuidance.REMINDER + "\n\n" + prompt else prompt
        service.prompt(text, pictures).whenComplete { reason, error ->
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater // the conversation was replaced meanwhile
                if (error != null) notice("Error: ${error.cause?.message ?: error.message}")
                else if (reason != "end_turn") notice("Stopped: $reason")
                setBusy(false)
                if (error != null || reason == "end_turn") {
                    val reply = (blocks.lastOrNull { it is ChatBlock.Agent } as? ChatBlock.Agent)?.text?.toString()
                    KiroNotifier.turnFinished(project, if (error != null) error.cause?.message ?: error.message else reply, error != null)
                }
                onSessionsChanged?.invoke() // the session now has a first prompt, and a newer timestamp
                if (error == null) refreshPlanUsage(force = true) // the turn just spent credits
                if (error != null || reason == "cancelled") holdWake = true
                deliverNotices()
            }
        }
    }

    /** Job news for the chat on screen goes to Kiro once it is idle; news for another chat waits until it is open. */
    private fun deliverNotices() {
        if (busy || opening || viewOnly || failed || holdWake) return
        val sid = service.currentSessionId() ?: return
        if (displayedId != null && displayedId != sid) return
        val notices = jobs.takeNotices(sid)
        if (notices.isEmpty()) return
        submit(ChatBlock.Note(notices.joinToString("\n") { "\u2699 ${it.summary}" }), WakePrompt.compose(notices), emptyList())
    }

    private fun onJobs(notice: Notice?) {
        updateStatus()
        if (notice == null) return
        val sid = service.currentSessionId()
        if (notice.sessionId == sid && (displayedId == null || displayedId == sid)) {
            holdWake = false // something new happened in this chat
        } else if (service.isPrimary && !service.isOpenAnywhere(notice.sessionId)) {
            // No open chat is the one it belongs to (every chat hears every notice; only one of them says so).
            KiroNotifier.jobEvent(project, notice.summary)
        }
        deliverNotices()
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
                    fail("Cannot start kiro-cli: ${error.cause?.message ?: error.message} — check Settings → Tools → Kiro")
                } else {
                    setFailed(false)
                    setTransient(null)
                    populate(info)
                }
                updateComposer()
                onSessionsChanged?.invoke()
                deliverNotices()
            }
        }
        onSessionsChanged?.invoke()
    }

    private fun setFailed(value: Boolean) {
        failed = value
        retryButton.isVisible = value
    }

    /** Shows [message] in the status line and offers the Retry button. */
    private fun fail(message: String) {
        setFailed(true)
        setTransient(message)
    }

    /**
     * Restarts the kiro-cli process and reconnects: first to the conversation that was open (its history is already
     * on screen, so the agent's replay is dropped), else to a fresh session.
     */
    /** Restarts kiro-cli for this chat (asks first when Kiro is mid-answer). */
    fun restart() = reconnect(confirmIfBusy = true)

    fun reconnect(confirmIfBusy: Boolean = false) {
        if (confirmIfBusy && busy &&
            Messages.showYesNoDialog(project, "Kiro is still working on a reply. Stop it and restart kiro-cli?", "Kiro", Messages.getWarningIcon()) != Messages.YES
        ) return
        val gen = ++generation
        setFailed(false)
        setBusy(false)
        opening = true
        val resumeId = (displayedId ?: lastSessionId)?.takeIf { !viewOnly }
        replayCovered = resumeId != null
        setTransient("Restarting kiro-cli…")
        updateComposer()
        val dir = lastCwd ?: project.basePath ?: System.getProperty("user.home")

        fun finish(info: SessionInfo?, error: Throwable?, resumed: Boolean) {
            ApplicationManager.getApplication().invokeLater {
                if (gen != generation) return@invokeLater
                opening = false
                replayCovered = false
                if (error != null || info == null) {
                    fail("Cannot start kiro-cli: ${error?.cause?.message ?: error?.message} — check Settings → Tools → Kiro")
                } else {
                    setTransient(null)
                    populate(info)
                    if (!resumed && resumeId != null && blocks.isNotEmpty()) {
                        notice("Reconnected with a new session — the messages above are history only.")
                    }
                    flash("Reconnected")
                }
                updateComposer()
                onSessionsChanged?.invoke()
                deliverNotices()
            }
        }

        service.restart().whenComplete { _, _ ->
            if (resumeId != null) {
                service.resume(resumeId, dir).whenComplete { info, error ->
                    if (error != null) service.newSession().whenComplete { i, e -> finish(i, e, resumed = false) }
                    else finish(info, null, resumed = true)
                }
            } else {
                service.start().whenComplete { info, error -> finish(info, error, resumed = false) }
            }
        }
        onSessionsChanged?.invoke()
    }

    private fun reset() {
        remindTools = true // the first prompt of every conversation carries the reminder, not only the first after a compaction
        blocks.clear()
        credits = 0.0
        usageText = ""
        setBusy(false)
        renderTranscript()
    }

    // ---------- state shown to the user ----------

    private fun setBusy(value: Boolean) {
        val changed = busy != value
        busy = value
        updateComposer()
        if (changed) onBusyChanged?.invoke(value)
    }

    /** Told when a turn starts or ends (the tab shows a spinner while its chat works). */
    var onBusyChanged: ((Boolean) -> Unit)? = null

    /** One place decides what the button, the hint inside the input and the status line say. */
    private fun updateComposer() {
        actionButton.stopping = busy
        actionButton.isEnabled = busy || (!(opening || viewOnly) && (input.text.isNotBlank() || attachments.isNotEmpty()))
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
        val running = jobs.activeCount(service.currentSessionId())
        val jobText = if (running > 0) "$running background job${if (running == 1) "" else "s"} running" else ""
        status.text = message ?: listOf(usageText, jobText).filter { it.isNotEmpty() }.joinToString("  \u00b7  ").ifEmpty { " " }
        status.foreground = if (message != null) UIUtil.getLabelForeground() else readable()
        statusShowsJobs = message == null && running > 0
        status.cursor = java.awt.Cursor.getPredefinedCursor(if (statusShowsJobs) java.awt.Cursor.HAND_CURSOR else java.awt.Cursor.DEFAULT_CURSOR)
        status.toolTipText = if (statusShowsJobs) "Show the Jobs tab" else null
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
                // The reminder the plugin put before the prompt is not the user's text.
                val shown = { t: String -> ToolGuidance.withoutReminder(t) }
                if (last is ChatBlock.User) blocks[blocks.lastIndex] = ChatBlock.User(shown(last.text + event.text)) else blocks.add(ChatBlock.User(shown(event.text)))
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
            is AcpEvent.ConfigOptions -> {
                populating = true
                try {
                    if (event.models.isNotEmpty()) fill(modelBox, event.models, event.currentModelId)
                    if (event.efforts.isNotEmpty()) {
                        fill(effortBox, event.efforts, event.currentEffortId)
                        effortBox.isVisible = true
                    }
                } finally {
                    populating = false
                }
            }
            is AcpEvent.Compacted -> {
                remindTools = true
                notice("The conversation was summarised to free context.")
            }
            is AcpEvent.Plan -> notice("Plan:\n" + event.entries.joinToString("\n") { "• $it" })
            is AcpEvent.Commands -> commands = event.commands
            is AcpEvent.DisplayError -> if (event.message != lastDisplayError) {
                lastDisplayError = event.message
                notice("⚠ ${event.message}")
            }
            is AcpEvent.Metadata -> {
                event.credits?.let { credits += it }
                val parts = listOfNotNull(
                    event.contextPercent?.let { "Context %.0f%%".format(it) },
                    if (credits > 0) "this chat %.2f credits".format(credits) else null,
                )
                if (parts.isNotEmpty()) {
                    usageText = parts.joinToString("  ·  ")
                    updateStatus()
                }
            }
            is AcpEvent.Closed -> {
                notice("kiro-cli exited${event.reason?.let { ": $it" } ?: ""}")
                opening = false
                setFailed(true)
                setBusy(false)
            }
            is AcpEvent.AgentThought, is AcpEvent.Other -> Unit
        }
    }

    // ---------- menus and clipboard ----------

    /** Slash commands advertised by Kiro plus the skills found on disk; picking one fills the input box. */
    private fun showCommandMenu() {
        val resources = KiroResources(project.basePath?.let { Path.of(it) }, Path.of(System.getProperty("user.home")))
        val entries = commands.map { MenuEntry(it.name, it.description) } +
            resources.skills().map { MenuEntry("/${it.name}", "skill (${it.scope.label}): ${it.summary}") }
        if (entries.isEmpty()) {
            notice("No slash commands yet — Kiro sends them once the session is ready.")
            return
        }
        JBPopupFactory.getInstance().createPopupChooserBuilder(entries)
            .setRenderer(menuRenderer())
            .setNamerForFiltering { "${it.name} ${it.detail}" }
            .setItemChosenCallback { chosen ->
                input.text = chosen.name + " "
                input.requestFocusInWindow()
            }
            .createPopup()
            .showUnderneathOf(slashButton)
    }

    private class MenuEntry(val name: String, val detail: String)

    /** Name, then the description in grey, cut with "…" so a long description can never widen the popup. */
    private fun menuRenderer() = object : ColoredListCellRenderer<MenuEntry>() {
        override fun customizeCellRenderer(list: javax.swing.JList<out MenuEntry>, value: MenuEntry?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            append(value.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            val small = getFontMetrics(JBUI.Fonts.smallFont())
            val room = MENU_WIDTH - getFontMetrics(font).stringWidth(value.name) - JBUI.scale(40)
            val text = Ellipsis.fit(value.detail.replace('\n', ' '), room, small)
            if (text.isNotEmpty()) append("   $text", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
        }
    }

    private fun handleLink(href: String, ctrl: Boolean) {
        val file = MarkdownHtml.parseFileLink(href)
        when {
            file != null -> if (ctrl) openFileRef(file) else flash("Hold Ctrl and click to open ${file.target}")
            href == "guidance:install" -> installGuidance()
            href.startsWith("tool:") -> toolCards().getOrNull(href.removePrefix("tool:").toIntOrNull() ?: -1)?.let { prefillInput(it.prompt) }
            href.startsWith("copycode:") -> copyCode(href)
            href.startsWith("copy:") -> copyBlock(href.removePrefix("copy:").toIntOrNull())
            href.startsWith("http://") || href.startsWith("https://") -> BrowserUtil.browse(href)
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
        val newSide = parts.getOrNull(2) == "new"
        CopyPasteManager.getInstance().setContents(StringSelection(if (newSide) DiffText.newSide(code) else code))
        flash(if (newSide) "New version copied" else "Code copied")
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
            accent = hex(ACCENT),
            border = hex(ColorUtil.mix(bg, fg, 0.17)),
            bg = hex(bg),
            ok = hex(JBColor(0x2E7D32, 0x62B543)),
            err = hex(JBColor(0xC62828, 0xFF6B68)),
            syntax = if (JBColor.isBright()) SyntaxColors.LIGHT else SyntaxColors.DARK,
            fontFamily = chatFontFamily(),
            fontSize = chatFontSize(),
            codeFont = try { com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.editorFontName } catch (_: Throwable) { null },
            diffAdd = hex(ColorUtil.mix(bg, JBColor(0x2E9E4F, 0x62B543), 0.20)),
            diffDel = hex(ColorUtil.mix(bg, JBColor(0xD93A3A, 0xFF6B68), 0.20)),
            diffHunk = hex(ColorUtil.mix(bg, ACCENT, 0.14)),
        )
    }

    private fun chatFontFamily(): String? = KiroSettings.getInstance().state.chatFontFamily.ifBlank { null }

    /** The IDE's UI font is on the small side for reading answers, so the default is one point larger. */
    private fun chatFontSize(): Int = KiroSettings.getInstance().state.chatFontSize.takeIf { it > 0 } ?: (JBUI.Fonts.label().size + 1)

    /** The input follows the same font as the transcript. */
    private fun applyFont() {
        val base = JBUI.Fonts.label()
        input.font = java.awt.Font(chatFontFamily() ?: base.family, java.awt.Font.PLAIN, chatFontSize())
        input.border = JBUI.Borders.empty(4, 4)
        input.revalidate()
    }

    /** Re-renders the whole transcript; keeps the user's scroll position unless they were following the tail. */
    private fun renderTranscript() {
        val empty = blocks.isEmpty()
        val cards = if (empty) toolCards() else emptyList()
        view.render(blocks, palette().copy(tools = cards, guidance = if (cards.isEmpty()) null else guidanceInstalled()), resolver, files.generation)
    }

    private fun resources() = KiroResources(project.basePath?.let { Path.of(it) }, Path.of(System.getProperty("user.home")))

    /** Is the steering file about the IDE tools in place (global or in the project)? Only asked while the chat is empty. */
    private fun guidanceInstalled(): Boolean = try {
        resources().steering().any { it.name == com.kirogui.jetbrains.jobs.ToolGuidance.FILE_NAME }
    } catch (_: Exception) {
        false
    }

    /** The empty chat's "Install Kiro guidance" button: global steering file, plus the project's when the setting says so. */
    private fun installGuidance() {
        try {
            val r = resources()
            r.ensureToolGuidance(com.kirogui.jetbrains.kiro.Scope.GLOBAL)
            if (KiroSettings.getInstance().state.guidanceInProject) r.ensureToolGuidance(com.kirogui.jetbrains.kiro.Scope.WORKSPACE)
            renderTranscript()
            flash("Installed ~/.kiro/steering/${com.kirogui.jetbrains.jobs.ToolGuidance.FILE_NAME}.md. Kiro reads it from the next message on.")
        } catch (e: Exception) {
            notice("Could not install the guidance: ${e.message}")
        }
    }

    private var cardsKey: Pair<Boolean, String>? = null
    private var cardsCache: List<com.kirogui.jetbrains.jobs.ToolCard> = emptyList()

    /** The tool cards for the empty chat; recomputed only when the settings that decide the tool list change. */
    private fun toolCards(): List<com.kirogui.jetbrains.jobs.ToolCard> {
        val s = KiroSettings.getInstance().state
        val key = s.ideTools to s.disabledTools
        if (key != cardsKey) {
            cardsKey = key
            cardsCache = com.kirogui.jetbrains.jobs.ToolShowcase.available(jobs.enabledToolNames())
        }
        return cardsCache
    }

    private fun choiceRenderer(label: String) = listRenderer<Choice> { l, v -> l.append(v?.name ?: label) }

    companion object {
        const val USAGE_MIN_INTERVAL_MS = 10_000L
        const val HEADER_NARROW_PX = 340
        val ACCENT = JBColor(0x7A3FF2, 0xB591FF)
        val MENU_WIDTH = JBUI.scale(520)
        const val DEFAULT_HINT = "Ask Kiro…   / commands · @ files and skills · Shift+Enter new line"
    }
}
