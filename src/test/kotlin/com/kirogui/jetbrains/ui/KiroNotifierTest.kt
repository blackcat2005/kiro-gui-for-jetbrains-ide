package com.kirogui.jetbrains.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KiroNotifierTest {

    @Test
    fun `summary is the first real line without markup`() {
        assertEquals("Fixed the login test", KiroNotifier.oneLine("\n## **Fixed** the `login` test\n\nmore"))
        assertEquals("Done", KiroNotifier.oneLine("```kotlin\nx\n```\nDone"))
        assertNull(KiroNotifier.oneLine("  \n "))
        assertNull(KiroNotifier.oneLine(null))
    }

    @Test
    fun `long summaries are cut`() {
        val out = KiroNotifier.oneLine("word ".repeat(100), 20)!!
        assertEquals(true, out.length <= 20 && out.endsWith("…"))
    }
}
