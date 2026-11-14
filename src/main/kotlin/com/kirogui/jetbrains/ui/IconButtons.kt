package com.kirogui.jetbrains.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * A small icon button for the panels' own header rows. Plain Swing on purpose: the IDE's action toolbars update their
 * buttons asynchronously and, in some themes and windows, never showed them, which left the headers empty.
 */
class IconButton(
    icon: Icon,
    tip: String,
    private val toggle: Boolean = false,
    selected: Boolean = false,
    private val onClick: (Boolean) -> Unit,
) : JComponent() {

    var selected: Boolean = selected
        set(value) {
            field = value
            repaint()
        }

    private var icon: Icon = icon

    /** Swaps the icon and tooltip (Send becomes Stop while Kiro works). */
    fun setIcon(newIcon: Icon, tip: String) {
        icon = newIcon
        toolTipText = tip
        repaint()
    }

    private var hover = false
    private var pressed = false

    init {
        toolTipText = tip
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val size = JBUI.size(26, 26)
        preferredSize = size
        minimumSize = size
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                pressed = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                pressed = true
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                val click = pressed && isEnabled && contains(e.point)
                pressed = false
                repaint()
                if (click) {
                    if (toggle) this@IconButton.selected = !this@IconButton.selected
                    onClick(this@IconButton.selected)
                }
            }
        })
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        cursor = Cursor.getPredefinedCursor(if (enabled) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            if (isEnabled && (pressed || hover || selected)) {
                g2.color = if (pressed || selected) JBUI.CurrentTheme.ActionButton.pressedBackground() else JBUI.CurrentTheme.ActionButton.hoverBackground()
                val arc = JBUI.scale(6)
                g2.fillRoundRect(0, 0, width, height, arc, arc)
            }
            val shown = if (isEnabled) icon else IconLoader.getDisabledIcon(icon)
            shown.paintIcon(this, g2, (width - shown.iconWidth) / 2, (height - shown.iconHeight) / 2)
        } finally {
            g2.dispose()
        }
    }
}

/** A row of [IconButton]s, left-aligned and tight. */
fun iconRow(vararg buttons: JComponent): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), JBUI.scale(1))).apply {
    isOpaque = false
    buttons.forEach { add(it) }
}

fun iconButton(icon: Icon, tip: String, onClick: () -> Unit) = IconButton(icon, tip) { onClick() }

fun iconToggle(icon: Icon, tip: String, selected: Boolean, onChange: (Boolean) -> Unit) = IconButton(icon, tip, toggle = true, selected = selected, onClick = onChange)

/** The composer's main button: a filled accent circle with an arrow (send) or a square (stop). */
class SendButton(private val onClick: () -> Unit) : JComponent() {
    var stopping: Boolean = false
        set(value) {
            field = value
            toolTipText = if (value) "Stop" else "Send (Enter)"
            repaint()
        }

    private var hover = false

    init {
        toolTipText = "Send (Enter)"
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        val size = JBUI.size(28, 28)
        preferredSize = size
        minimumSize = size
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                if (isEnabled && contains(e.point)) onClick()
            }
        })
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            val d = minOf(width, height) - JBUI.scale(2)
            val x = (width - d) / 2
            val y = (height - d) / 2
            val base = KiroChatPanel.ACCENT
            g2.color = when {
                !isEnabled -> java.awt.Color(base.red, base.green, base.blue, 90)
                hover -> base.brighter()
                else -> base
            }
            g2.fillOval(x, y, d, d)
            g2.color = java.awt.Color.WHITE
            val cx = width / 2f
            val cy = height / 2f
            val r = d * 0.26f
            if (stopping) {
                g2.fill(java.awt.geom.RoundRectangle2D.Float(cx - r, cy - r, 2 * r, 2 * r, r * 0.5f, r * 0.5f))
            } else {
                g2.stroke = java.awt.BasicStroke(JBUI.scale(2).toFloat(), java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND)
                g2.draw(java.awt.geom.Line2D.Float(cx, cy + r, cx, cy - r))
                g2.draw(java.awt.geom.Line2D.Float(cx - r * 0.9f, cy - r * 0.1f, cx, cy - r))
                g2.draw(java.awt.geom.Line2D.Float(cx + r * 0.9f, cy - r * 0.1f, cx, cy - r))
            }
        } finally {
            g2.dispose()
        }
    }
}

/** A rounded button with an icon and a word, for the few actions that deserve a name. Optionally a toggle. */
class PillButton(
    private val icon: javax.swing.Icon?,
    text: String,
    tip: String,
    private val toggle: Boolean = false,
    selected: Boolean = false,
    private val onClick: (Boolean) -> Unit,
) : JComponent() {

    var selected: Boolean = selected
        set(value) {
            field = value
            repaint()
        }

    var text: String = text
        private set

    private var hover = false
    private var pressed = false

    init {
        toolTipText = tip
        isOpaque = false
        isFocusable = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        font = JBUI.Fonts.label()
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hover = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hover = false
                pressed = false
                repaint()
            }

            override fun mousePressed(e: MouseEvent) {
                pressed = true
                repaint()
            }

            override fun mouseReleased(e: MouseEvent) {
                val click = pressed && isEnabled && contains(e.point)
                pressed = false
                repaint()
                if (click) {
                    if (toggle) this@PillButton.selected = !this@PillButton.selected
                    onClick(this@PillButton.selected)
                }
            }
        })
    }

    /** Changes the word on the button and the tooltip (e.g. "Jobs" becomes "Jobs · 2"). */
    fun setLabel(newText: String, tip: String) {
        text = newText
        toolTipText = tip
        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension {
        val m = getFontMetrics(font)
        val iconW = icon?.let { it.iconWidth + JBUI.scale(5) } ?: 0
        return Dimension(JBUI.scale(20) + iconW + m.stringWidth(text), maxOf(JBUI.scale(26), m.height + JBUI.scale(8)))
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            val arc = height
            val on = selected || pressed
            if (on || hover) {
                g2.color = if (on) JBUI.CurrentTheme.ActionButton.pressedBackground() else JBUI.CurrentTheme.ActionButton.hoverBackground()
                g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
            }
            g2.color = if (on) KiroChatPanel.ACCENT else com.intellij.ui.JBColor.border()
            g2.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
            var x = JBUI.scale(10)
            icon?.let {
                it.paintIcon(this, g2, x, (height - it.iconHeight) / 2)
                x += it.iconWidth + JBUI.scale(5)
            }
            g2.font = font
            g2.color = if (isEnabled) com.intellij.util.ui.UIUtil.getLabelForeground() else com.intellij.util.ui.UIUtil.getLabelDisabledForeground()
            val m = g2.fontMetrics
            g2.drawString(text, x, (height - m.height) / 2 + m.ascent)
        } finally {
            g2.dispose()
        }
    }
}

/** One height for the strip above the sessions list, the chat tabs and the jobs list, so they line up. */
const val PANEL_HEADER_HEIGHT = 34
