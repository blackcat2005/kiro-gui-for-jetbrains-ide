package com.kirogui.jetbrains.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EllipsisTest {

    private val width = { s: String -> s.length * 7 } // monospace stand-in for FontMetrics

    @Test
    fun `short text is left alone`() {
        assertEquals("hello", Ellipsis.fit("hello", 100, width))
    }

    @Test
    fun `long text is cut to fit with an ellipsis`() {
        val out = Ellipsis.fit("a very long description that would overflow the popup", 70, width)
        assertTrue(out.endsWith("…"), out)
        assertTrue(width(out) <= 70, "fits: $out")
        assertTrue(out.length > 3, "keeps as much as fits: $out")
    }

    @Test
    fun `no room means nothing`() {
        assertEquals("", Ellipsis.fit("anything", 0, width))
        assertEquals("", Ellipsis.fit("anything", 3, width))
    }
}
