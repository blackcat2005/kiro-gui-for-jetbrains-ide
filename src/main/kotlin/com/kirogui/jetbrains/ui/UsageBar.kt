package com.kirogui.jetbrains.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import javax.swing.JComponent

/** A thin rounded progress bar; colour is chosen by the caller (green / amber / red by how full it is). */
class UsageBar : JComponent() {

    var fraction: Double = 0.0
        set(value) {
            field = value.coerceIn(0.0, 1.0)
            repaint()
        }

    var fill: Color = JBColor.GRAY
        set(value) {
            field = value
            repaint()
        }

    init {
        isOpaque = false
        preferredSize = Dimension(JBUI.scale(56), JBUI.scale(6))
        minimumSize = preferredSize
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val h = height
            g2.color = TRACK
            g2.fillRoundRect(0, 0, width, h, h, h)
            if (fraction > 0) {
                // Never narrower than a dot, so a small amount of usage is still visible.
                val w = (width * fraction).toInt().coerceAtLeast(h)
                g2.color = fill
                g2.fillRoundRect(0, 0, w, h, h, h)
            }
        } finally {
            g2.dispose()
        }
    }

    private companion object {
        val TRACK = JBColor(Color(0xD8D8D8), Color(0x4A4D52))
    }
}
