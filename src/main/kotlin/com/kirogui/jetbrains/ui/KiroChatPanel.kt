package com.kirogui.jetbrains.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColorUtil
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.openapi.ui.ComboBox
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kirogui.jetbrains.acp.AcpEvent
import com.kirogui.jetbrains.acp.Choice
import com.kirogui.jetbrains.acp.SessionInfo
import com.kirogui.jetbrains.acp.SlashCommand
import com.kirogui.jetbrains.kiro.KiroResources
import com.intellij.openapi.ui.popup.JBPopupFactory
import java.nio.file.Path
import com.kirogui.jetbrains.service.KiroAcpService
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent

/**
 * Chat view: Markdown transcript, agent/model pickers, context usage.
 * Enter sends, Shift+Enter inserts a newline.
 */
class KiroChatPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {

    private sealed interface Block {
        class User(val text: String) : Block
        class Agent(val text: StringBuilder = StringBuilder()) : Block
        class Note(val text: String) : Block
    }

    private val service = project.getService(KiroAcpService::class.java)
    private val blocks = mutableListOf<Block>()

    private val transcript = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build().also { kit ->
            val fg = ColorUtil.toHex(UIUtil.getLabelForeground())
            val bg = UIUtil.getTextFieldBackground()
            val codeBg = ColorUtil.toHex(ColorUtil.mix(bg, UIUtil.getLabelForeground(), 0.1))
            kit.styleSheet.addRule("body { color: #$fg; margin: 6px; }")
            kit.styleSheet.addRule("pre { background-color: #$codeBg; padding: 6px; }")
            kit.styleSheet.addRule("code { background-color: #$codeBg; }")
            kit.styleSheet.addRule(".user { background-color: #$codeBg; padding: 4px; }")
        }
        background = UIUtil.getTextFieldBackground()
        addHyperlinkListener { e ->
            if (e.eventType == HyperlinkEvent.EventType.ACTIVATED) e.url?.let { BrowserUtil.browse(it.toString()) }
        }
    }
    private val scroll = JBScrollPane(transcript)

    private val input = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val status = JBLabel(" ")
    private val usage = JBLabel(" ")

    private val agentBox = ComboBox<Choice>().apply { renderer = choiceRenderer("Agent") }
    private val modelBox = ComboBox<Choice>().apply { renderer = choiceRenderer("Model") }
    private val slashButton = JButton("/")
    private var commands: List<SlashCommand> = emptyList()
    private var populating = false
    private var credits = 0.0

    private val renderTimer = Timer(60) { renderTranscript() }.apply { isRepeats = false }

    init {
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(agentBox)
            add(modelBox)
            add(slashButton)
            add(usage)
        }
        val buttons = JPanel().apply {
            add(sendButton)
            add(stopButton)
            add(JButton("New chat").also { b -> b.addActionListener { newChat() } })
        }
        val bottom = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4)
            add(status, BorderLayout.NORTH)
            add(JBScrollPane(input), BorderLayout.CENTER)
            add(buttons, BorderLayout.EAST)
        }
        add(top, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
        add(bottom, BorderLayout.SOUTH)

        slashButton.toolTipText = "Slash commands and skills"
        slashButton.addActionListener { showCommandMenu() }
        sendButton.addActionListener { send() }
        stopButton.addActionListener { service.cancel() }
        input.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER && !e.isShiftDown) {
                    e.consume()
                    send()
                }
            }
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
        connect()
    }

    /** Insert text (e.g. a code selection from the editor) into the input box. */
    fun appendToInput(text: String) {
        input.append(if (input.text.isEmpty() || input.text.endsWith("\n")) text else "\n$text")
        input.requestFocusInWindow()
    }

    /** Replaces the conversation with a stored Kiro session; Kiro replays its history into the transcript. */
    fun loadSession(sessionId: String, cwd: String?) {
        blocks.clear()
        credits = 0.0
        usage.text = " "
        renderTranscript()
        setBusy(false)
        status.text = "Loading session…"
        val dir = cwd?.takeIf { java.io.File(it).isDirectory } ?: project.basePath ?: System.getProperty("user.home")
        service.resume(sessionId, dir).whenComplete { info, error ->
            ApplicationManager.getApplication().invokeLater {
                if (error != null) {
                    status.text = " "
                    notice("Could not load session: ${error.cause?.message ?: error.message}")
                } else {
                    status.text = " "
                    populate(info)
                }
            }
        }
    }

    private fun connect() {
        status.text = "Connecting to Kiro…"
        service.start().whenComplete { info, error ->
            ApplicationManager.getApplication().invokeLater {
                if (error != null) {
                    status.text = "Cannot start kiro-cli: ${error.cause?.message ?: error.message} (check Settings → Tools → Kiro)"
                } else {
                    status.text = " "
                    populate(info)
                }
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
        if (text.isEmpty() || !sendButton.isEnabled) return
        input.text = ""
        blocks.add(Block.User(text))
        scheduleRender()
        setBusy(true)
        service.prompt(text).whenComplete { reason, error ->
            ApplicationManager.getApplication().invokeLater {
                if (error != null) notice("Error: ${error.cause?.message ?: error.message}")
                else if (reason != "end_turn") notice("Stopped: $reason")
                setBusy(false)
            }
        }
    }

    private fun newChat() {
        service.reset()
        blocks.clear()
        credits = 0.0
        usage.text = " "
        renderTranscript()
        setBusy(false)
        connect()
    }

    private fun setBusy(busy: Boolean) {
        sendButton.isEnabled = !busy
        stopButton.isEnabled = busy
        status.text = if (busy) "Kiro is working…" else " "
    }

    private fun onEvent(event: AcpEvent) {
        when (event) {
            is AcpEvent.AgentText -> {
                (blocks.lastOrNull() as? Block.Agent ?: Block.Agent().also { blocks.add(it) }).text.append(event.text)
                scheduleRender()
            }
            is AcpEvent.UserText -> if (event.replay) {
                val last = blocks.lastOrNull()
                if (last is Block.User) blocks[blocks.lastIndex] = Block.User(last.text + event.text) else blocks.add(Block.User(event.text))
                scheduleRender()
            }
            is AcpEvent.ToolCall -> notice("🔧 ${event.title}")
            is AcpEvent.ToolCallUpdate -> if (event.status == "failed") notice("Tool failed: ${event.title ?: event.id}")
            is AcpEvent.Plan -> notice("Plan:\n" + event.entries.joinToString("\n") { "• $it" })
            is AcpEvent.Commands -> commands = event.commands
            is AcpEvent.Metadata -> {
                event.credits?.let { credits += it }
                val parts = listOfNotNull(
                    event.contextPercent?.let { "context %.1f%%".format(it) },
                    if (credits > 0) "%.3f credits".format(credits) else null,
                )
                if (parts.isNotEmpty()) usage.text = parts.joinToString(" · ")
            }
            is AcpEvent.Closed -> {
                notice("kiro-cli exited${event.reason?.let { ": $it" } ?: ""}")
                setBusy(false)
            }
            is AcpEvent.AgentThought, is AcpEvent.Other -> Unit
        }
    }

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
            .showUnderneathOf(slashButton)
    }

    private fun notice(text: String) {
        blocks.add(Block.Note(text))
        scheduleRender()
    }

    private fun scheduleRender() {
        if (!renderTimer.isRunning) renderTimer.start()
    }

    /** Re-renders the whole transcript; keeps the user's scroll position unless they were following the tail. */
    private fun renderTranscript() {
        val bar = scroll.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 8
        val previous = bar.value
        val html = StringBuilder("<html><body>")
        for (b in blocks) {
            when (b) {
                is Block.User -> html.append("<div class=\"user\"><b>You</b><br>").append(MarkdownHtml.escape(b.text)).append("</div>")
                is Block.Agent -> html.append(MarkdownHtml.render(b.text.toString()))
                is Block.Note -> html.append("<p><i>").append(MarkdownHtml.escape(b.text)).append("</i></p>")
            }
        }
        html.append("</body></html>")
        transcript.text = html.toString()
        SwingUtilities.invokeLater { bar.value = if (atBottom) bar.maximum else previous }
    }

    private fun choiceRenderer(label: String) = SimpleListCellRenderer.create<Choice>(label) { it.name }
}
