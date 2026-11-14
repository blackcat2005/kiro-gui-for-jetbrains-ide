package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IconButtonTest {

    @Test
    fun `buttons can be built, laid out and painted`() {
        var clicked = 0
        val plain = iconButton(AllIcons.Actions.Refresh, "Refresh") { clicked++ }
        val toggle = iconToggle(AllIcons.General.Filter, "Filter", selected = true) { }
        val row = iconRow(plain, toggle)
        row.setSize(120, 30)
        row.doLayout()
        val image = BufferedImage(120, 30, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            row.paint(g)
            plain.isEnabled = false
            row.paint(g)
        } finally {
            g.dispose()
        }
        assertEquals("Refresh", plain.toolTipText)
        assertTrue(toggle.selected)
        assertEquals(0, clicked)
    }
}
