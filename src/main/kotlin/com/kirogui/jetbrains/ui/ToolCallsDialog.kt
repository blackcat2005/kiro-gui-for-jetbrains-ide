package com.kirogui.jetbrains.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.mcp.ToolCall
import com.kirogui.jetbrains.mcp.ToolCallLog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.datatransfer.StringSelection
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSplitPane
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/** The history of what Kiro asked the IDE tools to do: time, tool, status, duration; arguments and result below. */
class ToolCallsDialog(project: Project, private val log: ToolCallLog, private val toolList: () -> String) : DialogWrapper(project, false) {

    private val filter = JBTextField().apply { emptyText.text = "Filter by tool, arguments or result" }
    private val model = object : DefaultTableModel(arrayOf("Time", "Tool", "Status", "ms"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val table = JBTable(model).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val detail = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(6)
    }
    private var shown: List<ToolCall> = emptyList()
    private val unsubscribe: () -> Unit

    init {
        title = "Kiro tool calls"
        init()
        filter.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = reload()
            override fun removeUpdate(e: DocumentEvent) = reload()
            override fun changedUpdate(e: DocumentEvent) = reload()
        })
        table.selectionModel.addListSelectionListener { if (!it.valueIsAdjusting) showDetail() }
        unsubscribe = log.addListener { javax.swing.SwingUtilities.invokeLater { reload() } }
        reload()
    }

    override fun dispose() {
        unsubscribe()
        super.dispose()
    }

    private fun matching(): List<ToolCall> {
        val q = filter.text.trim().lowercase()
        val all = log.snapshot()
        return if (q.isEmpty()) all else all.filter { q in it.tool.lowercase() || q in it.arguments.lowercase() || q in it.result.lowercase() }
    }

    private fun reload() {
        val keep = table.selectedRow.takeIf { it >= 0 }?.let { shown.getOrNull(it) }
        shown = matching()
        val time = SimpleDateFormat("HH:mm:ss")
        model.rowCount = 0
        shown.forEach { model.addRow(arrayOf(time.format(Date(it.time)), it.tool, if (it.ok) "ok" else "error", it.millis)) }
        val again = shown.indexOf(keep)
        if (again >= 0) table.setRowSelectionInterval(again, again) else if (shown.isNotEmpty()) table.setRowSelectionInterval(0, 0)
        showDetail()
    }

    private fun showDetail() {
        val call = table.selectedRow.takeIf { it >= 0 }?.let { shown.getOrNull(it) }
        detail.text = call?.let {
            "${it.tool}  ·  ${if (it.ok) "ok" else "error"}  ·  ${it.millis} ms\n" +
                (it.sessionId?.let { s -> "chat session: $s\n" } ?: "") + "\narguments:\n${it.arguments}\n\nresult:\n${it.result}"
        } ?: (if (log.snapshot().isEmpty()) "Nothing yet. Calls Kiro makes to bg_run, test_run, debug_* and the other IDE tools show up here. \"Available tools\" lists them all." else "")
        detail.caretPosition = 0
    }

    override fun createCenterPanel(): JComponent {
        val top = JPanel(BorderLayout()).apply {
            add(filter, BorderLayout.NORTH)
            add(JBScrollPane(table), BorderLayout.CENTER)
        }
        val split = JSplitPane(JSplitPane.VERTICAL_SPLIT, top, JBScrollPane(detail)).apply {
            resizeWeight = 0.45
            border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0)
        }
        return JPanel(BorderLayout()).apply {
            preferredSize = Dimension(JBUI.scale(760), JBUI.scale(520))
            add(split, BorderLayout.CENTER)
        }
    }

    override fun createActions(): Array<Action> = arrayOf(
        object : DialogWrapperAction("Available tools") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                table.clearSelection()
                detail.text = "Tools Kiro gets from the IDE ([off] = turned off in Settings > Tools > Kiro):\n\n" + toolList()
                detail.caretPosition = 0
            }
        },
        object : DialogWrapperAction("Copy JSON") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                com.intellij.openapi.ide.CopyPasteManager.getInstance().setContents(StringSelection(ToolCallLog.toJson(shown)))
            }
        },
        object : DialogWrapperAction("Export…") {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                val wrapper = FileChooserFactory.getInstance()
                    .createSaveFileDialog(FileSaverDescriptor("Export Kiro tool calls", "Saves the listed calls as JSON", "json"), contentPanel)
                    .save("kiro-tool-calls.json") ?: return
                wrapper.file.writeText(ToolCallLog.toJson(shown))
            }
        },
        object : DialogWrapperAction("Clear") {
            override fun doAction(e: java.awt.event.ActionEvent?) = log.clear()
        },
        okAction.also { it.putValue(Action.NAME, "Close") },
    )
}
