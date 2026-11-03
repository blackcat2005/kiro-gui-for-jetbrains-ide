package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.kiro.Completion
import com.kirogui.jetbrains.kiro.CompletionEngine
import com.kirogui.jetbrains.kiro.Suggestion
import com.kirogui.jetbrains.kiro.SuggestionKind
import java.awt.Dimension
import java.awt.Point
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import javax.swing.JList
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.BadLocationException

/**
 * Suggestion popup for the chat input. It never takes focus, so typing continues normally; Up/Down move,
 * Enter or Tab accept, Esc dismisses. The popup opens above the caret because the input sits at the bottom.
 */
class InputCompletion(
    private val input: JBTextArea,
    private val engine: CompletionEngine,
    private val parent: Disposable,
) {
    private val model = CollectionListModel<Suggestion>()
    private val list = JBList(model).apply {
        fixedCellHeight = JBUI.scale(24)
        cellRenderer = object : ColoredListCellRenderer<Suggestion>() {
            override fun customizeCellRenderer(list: JList<out Suggestion>, value: Suggestion?, index: Int, selected: Boolean, hasFocus: Boolean) {
                value ?: return
                icon = when (value.kind) {
                    SuggestionKind.COMMAND, SuggestionKind.SUBCOMMAND -> AllIcons.Actions.Lightning
                    SuggestionKind.SKILL -> AllIcons.Nodes.Plugin
                    SuggestionKind.FILE -> FileTypeManager.getInstance().getFileTypeByFileName(value.label).icon
                }
                append(value.label, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (value.detail.isNotEmpty()) {
                    // Whatever is left of the row after the icon and the name; a long description ends in "…".
                    val used = icon?.iconWidth.orZero() + getFontMetrics(font).stringWidth(value.label) + JBUI.scale(40)
                    val room = POPUP_WIDTH - used
                    val small = getFontMetrics(JBUI.Fonts.smallFont())
                    val text = Ellipsis.fit(value.detail.replace("\n", " "), room - small.stringWidth("   "), small)
                    if (text.isNotEmpty()) append("   $text", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
        }
    }
    private val scroll = JBScrollPane(list).apply { horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER }

    /** When set, picking a file with "@" calls this (with the project-relative path) instead of inserting text. */
    var onFile: ((String) -> Unit)? = null

    private var popup: JBPopup? = null
    private var current: Completion? = null
    private var pending = false
    private var applying = false

    fun install() {
        input.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = schedule()
            override fun removeUpdate(e: DocumentEvent) = schedule()
            override fun changedUpdate(e: DocumentEvent) = schedule()
        })
        input.addCaretListener { schedule() }
        input.addFocusListener(object : FocusAdapter() {
            override fun focusLost(e: FocusEvent) = hide()
        })
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 1) {
                    list.locationToIndex(e.point).takeIf { it >= 0 }?.let { list.selectedIndex = it; accept() }
                }
            }
        })
        Disposer.register(parent) { hide() }
    }

    /** Returns true when the key was used by the popup (the caller must then not handle it). */
    fun handleKey(e: KeyEvent): Boolean {
        if (popup?.isVisible != true) return false
        when (e.keyCode) {
            KeyEvent.VK_DOWN -> move(1)
            KeyEvent.VK_UP -> move(-1)
            KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> accept()
            KeyEvent.VK_ESCAPE -> hide()
            else -> return false
        }
        e.consume()
        return true
    }

    private fun move(delta: Int) {
        val n = model.size
        if (n == 0) return
        list.selectedIndex = (list.selectedIndex + delta + n) % n
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    private fun accept() {
        val c = current ?: return
        val chosen = list.selectedValue ?: return
        applying = true
        try {
            val path = chosen.path
            if (chosen.kind == SuggestionKind.FILE && path != null && onFile != null) {
                // A file mention becomes a chip next to the input rather than text inside the prompt.
                input.replaceRange("", c.start, c.end)
                input.caretPosition = c.start
                onFile?.invoke(path)
            } else {
                input.replaceRange(chosen.insert, c.start, c.end)
                input.caretPosition = c.start + chosen.insert.length
            }
        } finally {
            applying = false
        }
        hide()
    }

    private fun schedule() {
        if (applying || pending) return
        pending = true
        // Never touch the popup from inside a document/caret notification.
        SwingUtilities.invokeLater {
            pending = false
            refresh()
        }
    }

    private fun refresh() {
        if (!input.isShowing || !input.hasFocus()) return hide()
        val completion = engine.complete(input.text, input.caretPosition)
        if (completion == null || completion.suggestions.isEmpty()) return hide()
        current = completion
        model.replaceAll(completion.suggestions)
        list.selectedIndex = 0
        show(completion.suggestions.size)
    }

    private fun show(count: Int) {
        val rows = count.coerceAtMost(8)
        val size = Dimension(POPUP_WIDTH, rows * list.fixedCellHeight + JBUI.scale(6))
        val at = anchor(size.height) ?: return hide()
        val existing = popup
        if (existing != null && existing.isVisible) {
            existing.size = size
            existing.setLocation(at)
            return
        }
        val created = JBPopupFactory.getInstance().createComponentPopupBuilder(scroll, null)
            .setRequestFocus(false)
            .setFocusable(false)
            .setMovable(false)
            .setResizable(false)
            .setCancelOnClickOutside(true)
            .setCancelKeyEnabled(false)
            .createPopup()
        Disposer.register(parent, created)
        popup = created
        created.size = size
        created.showInScreenCoordinates(input, at)
    }

    /** Screen position just above the line the caret is on. */
    private fun anchor(popupHeight: Int): Point? = try {
        val r = input.modelToView2D(input.caretPosition) ?: return null
        val p = Point(r.x.toInt(), r.y.toInt())
        SwingUtilities.convertPointToScreen(p, input)
        Point(p.x, p.y - popupHeight - JBUI.scale(4))
    } catch (_: BadLocationException) {
        null
    }

    private fun hide() {
        popup?.cancel()
        popup = null
        current = null
    }

    private fun Int?.orZero() = this ?: 0

    private companion object {
        val POPUP_WIDTH = JBUI.scale(520)
    }
}
