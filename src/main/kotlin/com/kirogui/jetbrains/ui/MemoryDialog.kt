package com.kirogui.jetbrains.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.acp.Memory
import java.awt.Dimension
import javax.swing.Action
import javax.swing.JComponent

/** One of Kiro's memories, read in full. */
class MemoryDialog(project: Project?, private val memory: Memory) : DialogWrapper(project, false) {
    init {
        title = memory.title.ifBlank { "Memory" }
        setOKButtonText("Close")
        init()
    }

    override fun createActions(): Array<Action> = arrayOf(okAction)

    override fun createCenterPanel(): JComponent {
        val kind = if (memory.isPreference) "Your preference" else "About a project"
        val text = buildString {
            append(kind)
            if (memory.scopes.isNotEmpty()) append("  ·  ").append(memory.scopes.joinToString(", "))
            memory.updatedAt?.let { append("  ·  ").append(it.take(10)) }
            if (memory.summary.isNotBlank()) append("\n\n").append(memory.summary)
            append("\n\n").append(memory.content.trim())
            memory.why?.takeIf { it.isNotBlank() }?.let { append("\n\nWhy: ").append(it.trim()) }
            memory.howToApply?.takeIf { it.isNotBlank() }?.let { append("\n\nHow to apply: ").append(it.trim()) }
        }
        val area = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            border = JBUI.Borders.empty(6)
            caretPosition = 0
        }
        return JBScrollPane(area).apply { preferredSize = Dimension(JBUI.scale(560), JBUI.scale(360)) }
    }
}
