package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorFontType
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kirogui.jetbrains.jobs.Job
import com.kirogui.jetbrains.jobs.JobKind
import com.kirogui.jetbrains.jobs.JobService
import com.kirogui.jetbrains.jobs.JobState
import com.kirogui.jetbrains.jobs.JobText
import java.awt.BorderLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.Timer

/**
 * The Jobs tab: everything Kiro runs in the background (bg_run commands, debug_start sessions), across chats. Shows
 * each job's state and live output and lets the user stop it, bring up its console, or hand its output to the chat.
 */
class KiroJobsPanel(
    private val project: Project,
    parent: Disposable,
    private val currentSessionId: () -> String?,
    /** Puts the text in the chat as an attachment. */
    private val onAttach: (Attachment) -> Unit,
    /** How many jobs are active, for the tab title. */
    private val onActiveCount: (Int) -> Unit,
    /** Closes the panel (it sits under the chat); null: no Hide button. */
    private val onHide: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val jobs = project.getService(JobService::class.java)
    private val model = CollectionListModel<Job>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = Renderer()
        emptyText.text = "No background jobs yet"
        emptyText.appendSecondaryText("Kiro starts them with bg_run, test_run and debug_start", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        visibleRowCount = 1
    }
    private val listScroll = JBScrollPane(list, JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER).apply {
        border = JBUI.Borders.customLineBottom(JBColor.border())
    }
    private val header = JBLabel(" ").apply { border = JBUI.Borders.empty(4, 8) }
    private val detail = JBLabel(" ").apply {
        border = JBUI.Borders.empty(0, 8, 4, 8)
        foreground = UIUtil.getContextHelpForeground()
    }
    private val output = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = EditorColorsManager.getInstance().globalScheme.getFont(EditorFontType.PLAIN)
        border = JBUI.Borders.empty(4, 8)
        // The caret must not drag the view: every refresh replaces the text, and a caret at the end of a long last line
        // scrolled the log sideways, so the start of the lines could not be reached. Scrolling is done by hand below.
        (caret as? javax.swing.text.DefaultCaret)?.updatePolicy = javax.swing.text.DefaultCaret.NEVER_UPDATE
    }
    private val outputScroll = JBScrollPane(output)

    private val stopButton = iconButton(AllIcons.Actions.Suspend, "Stop the selected job (Kiro is told you stopped it)") {
        list.selectedValue?.let { jobs.stopByUser(it) }
        refresh()
    }
    private val consoleButton = iconButton(AllIcons.Toolwindows.ToolWindowRun, "Show the job in the Run or Debug tool window") {
        list.selectedValue?.let(::reveal)
    }
    private val attachButton = iconButton(AllIcons.General.Add, "Add the job's output (a debug helper: its whole session) to your next message") {
        list.selectedValue?.let(::attachOutput)
    }
    private val clearButton = iconButton(AllIcons.Actions.GC, "Remove ended jobs from this list") {
        jobs.clearEnded()
        refresh()
    }
    private val callsButton = iconButton(AllIcons.Actions.ListFiles, "Tool calls: everything Kiro asked the IDE tools to do") {
        ToolCallsDialog(project, jobs.callLog, jobs::toolList).show()
    }
    private val hideButton = onHide?.let { hide -> iconButton(AllIcons.General.HideToolWindow, "Hide this panel (jobs keep running)") { hide() } }

    private val bottom = JPanel(BorderLayout())
    private lateinit var northPanel: JPanel
    private var arranged: Boolean? = null

    /** With jobs: the list on top and the output below. Without: just the (empty) list, filling the panel. */
    private fun arrange(any: Boolean) {
        if (arranged == any) return
        arranged = any
        removeAll()
        if (any) {
            add(northPanel, BorderLayout.NORTH)
            add(bottom, BorderLayout.CENTER)
        } else {
            add(northPanel, BorderLayout.CENTER)
        }
        listScroll.border = if (any) JBUI.Borders.customLineBottom(JBColor.border()) else JBUI.Borders.empty()
        revalidate()
        repaint()
    }

    private var shownId: Int? = null
    private var shownChars = -1L
    private var shownReport: String? = null
    private var lastActive = -1
    private val timer = Timer(1000) { refresh() }

    init {
        val top = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLineBottom(JBColor.border())
            preferredSize = java.awt.Dimension(0, JBUI.scale(PANEL_HEADER_HEIGHT))
            add(iconRow(*listOfNotNull(stopButton, consoleButton, attachButton, clearButton, callsButton, hideButton).toTypedArray()), BorderLayout.WEST)
        }

        list.addListSelectionListener { if (!it.valueIsAdjusting) showSelected(force = true) }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) list.selectedValue?.let(::reveal)
            }
        })

        bottom.apply {
            add(JPanel(BorderLayout()).apply {
                border = JBUI.Borders.customLineBottom(JBColor.border())
                add(header, BorderLayout.NORTH)
                add(detail, BorderLayout.CENTER)
            }, BorderLayout.NORTH)
            add(outputScroll, BorderLayout.CENTER)
        }
        val north = JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(listScroll, BorderLayout.CENTER)
        }
        northPanel = north
        arrange(any = false)

        jobs.addListener(parent) {
            ApplicationManager.getApplication().invokeLater({ refresh() }, ModalityState.any())
        }
        timer.start()
        Disposer.register(parent) { timer.stop() }
        refresh()
    }

    /** Selects [job] (when the chat's "N background jobs running" is clicked, the newest active one of that chat). */
    fun select(job: Job?) {
        refresh()
        val target = job ?: model.items.firstOrNull { it.state.active && it.sessionId == currentSessionId() } ?: model.items.firstOrNull()
        target?.let { list.setSelectedValue(it, true) }
    }

    private fun refresh() {
        val all = jobs.all().sortedWith(compareByDescending<Job> { it.state.active }.thenByDescending { it.id })
        if (all.map { it.id } != model.items.map { it.id }) {
            val selected = list.selectedValue?.id
            model.replaceAll(all)
            val again = all.indexOfFirst { it.id == selected }
            if (again >= 0) list.selectedIndex = again
            else if (all.isNotEmpty()) list.selectedIndex = 0
        } else {
            list.repaint() // durations and states
        }
        val rows = all.size.coerceIn(1, MAX_ROWS)
        if (list.visibleRowCount != rows) {
            list.visibleRowCount = rows
            listScroll.revalidate()
        }
        val active = all.count { it.state.active }
        if (active != lastActive) {
            lastActive = active
            onActiveCount(active)
        }
        showSelected(force = false)
    }

    private fun updateButtons() {
        val job = list.selectedValue
        // Nothing to act on yet: no row of greyed-out buttons, no empty output area.
        val any = model.size > 0
        listOf(stopButton, consoleButton, attachButton, clearButton).forEach { it.isVisible = any }
        bottom.isVisible = any
        arrange(any)
        stopButton.isEnabled = job?.state?.active == true
        consoleButton.isEnabled = job != null
        attachButton.isEnabled = job != null && job.output.totalChars > 0
        clearButton.isEnabled = model.items.any { !it.state.active }
    }

    private fun showSelected(force: Boolean) {
        updateButtons()
        val job = list.selectedValue
        if (job == null) {
            header.text = " "
            detail.text = " "
            output.text = ""
            shownId = null
            return
        }
        header.icon = icon(job)
        // The list row already says "#1 title, exit 0, 12s"; this line adds the outcome in words and the command.
        header.isVisible = false
        detail.text = "${JobText.outcome(job)} · ${JobText.duration(job.elapsedMillis())} · " +
            JobText.what(job).lineSequence().first() +
            (if (job.sessionId != null && job.sessionId != currentSessionId()) "   (started from another chat)" else "")
        val chars = job.output.totalChars
        val report = job.report
        if (!force && job.id == shownId && chars == shownChars && report == shownReport) return
        shownReport = report
        val bar = outputScroll.verticalScrollBar
        val hbar = outputScroll.horizontalScrollBar
        val sameJob = job.id == shownId
        val follow = !sameJob || bar.value + bar.visibleAmount >= bar.maximum - 4
        val top = if (sameJob) bar.value else 0
        val left = if (sameJob) hbar.value else 0 // a new job starts at the left edge; the same one stays where you scrolled
        shownId = job.id
        shownChars = chars
        val text = job.output.tail(VIEW_LINES, VIEW_CHARS).ifEmpty { if (job.state.active) "(no output yet)" else "(no output)" }
        // A test run: the report Kiro got, then the console.
        output.text = if (report != null) "$report\n\n──── output ────\n$text" else text
        // After the new text is laid out: stick to the bottom if you were there, and never move sideways by itself.
        javax.swing.SwingUtilities.invokeLater {
            bar.value = if (follow) bar.maximum else top
            hbar.value = left
        }
    }

    private fun reveal(job: Job) {
        val manager = RunContentManager.getInstance(project)
        val descriptor = job.process?.let { handler -> manager.allDescriptors.firstOrNull { it.processHandler === handler } }
        val window = descriptor?.let { manager.getToolWindowByDescriptor(it) }
        if (descriptor != null && window != null) {
            window.activate { manager.selectRunContent(descriptor) }
        } else {
            val id = if (job.kind == JobKind.DEBUG) ToolWindowId.DEBUG else ToolWindowId.RUN
            ToolWindowManager.getInstance(project).getToolWindow(id)?.activate(null)
        }
    }

    /** The whole conversation of a debug helper (its questions, steps and replies), read from Kiro's session files. */
    private fun helperTranscript(job: Job): String? {
        val id = job.helperSessionId ?: return null
        val home = java.nio.file.Path.of(System.getProperty("user.home"), ".kiro")
        val session = com.kirogui.jetbrains.kiro.SessionScanner(home).scan().firstOrNull { it.id == id } ?: return null
        val items = com.kirogui.jetbrains.kiro.TranscriptReader.read(session)
        if (items.isEmpty()) return null
        return items.joinToString("\n\n") {
            when (it) {
                is com.kirogui.jetbrains.kiro.PreviewItem.User -> "[task]\n" + it.text
                is com.kirogui.jetbrains.kiro.PreviewItem.Assistant -> "[helper]\n" + it.text
                is com.kirogui.jetbrains.kiro.PreviewItem.Tool -> "• " + it.title
            }
        }.takeLast(ATTACH_CHARS)
    }

    private fun attachOutput(job: Job) {
        helperTranscript(job)?.let {
            onAttach(Attachment.Snippet(it, "", "Debug helper #${job.id} session (${job.title}), ${JobText.outcome(job)}"))
            return
        }
        val text = job.output.tail(ATTACH_LINES, ATTACH_CHARS)
        if (text.isBlank()) return
        onAttach(Attachment.Snippet(text, "", "Job #${job.id} (${job.title}) output, ${JobText.outcome(job)}"))
    }

    private inner class Renderer : ColoredListCellRenderer<Job>() {
        override fun customizeCellRenderer(list: JList<out Job>, job: Job, index: Int, selected: Boolean, hasFocus: Boolean) {
            icon = icon(job)
            append("#${job.id}  ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(job.title, if (job.state.active) SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES else SimpleTextAttributes.REGULAR_ATTRIBUTES)
            append("   ${shortStatus(job)} · ${JobText.duration(job.elapsedMillis())}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (job.sessionId != null && job.sessionId != currentSessionId()) append("   other chat", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            toolTipText = job.detail
        }
    }

    /** What fits beside the title in a narrow list: "running", "exit 0", "3 of 17 tests failed". */
    private fun shortStatus(job: Job): String = when {
        job.state.active -> job.state.label
        job.summary != null -> job.summary!!
        job.state == JobState.FINISHED -> "exit ${job.exitCode ?: 0}"
        job.state == JobState.FAILED -> job.exitCode?.let { "exit $it" } ?: "failed"
        else -> if (job.stoppedByUser.get()) "stopped by you" else "stopped"
    }

    private fun icon(job: Job): Icon = when (job.state) {
        JobState.STARTING -> AnimatedIcon.Default.INSTANCE
        JobState.RUNNING -> if (job.kind == JobKind.DEBUG) AllIcons.Actions.StartDebugger else AllIcons.Actions.Execute
        JobState.PAUSED -> AllIcons.Actions.Pause
        JobState.FINISHED -> AllIcons.RunConfigurations.TestPassed
        JobState.FAILED -> AllIcons.RunConfigurations.TestError
        JobState.STOPPED -> AllIcons.Actions.Suspend
    }

    private companion object {
        const val MAX_ROWS = 5
        const val VIEW_LINES = 5000
        const val VIEW_CHARS = 250_000
        const val ATTACH_LINES = 300
        const val ATTACH_CHARS = 30_000
    }
}
