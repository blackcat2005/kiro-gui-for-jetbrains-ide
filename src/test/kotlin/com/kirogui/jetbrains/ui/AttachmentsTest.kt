package com.kirogui.jetbrains.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentsTest {

    @Test
    fun `no attachments leaves the prompt alone`() {
        assertEquals("hello", Attachments.compose("  hello ", emptyList()))
    }

    @Test
    fun `files are named and snippets are fenced after the prompt`() {
        val text = Attachments.compose(
            "Why does this fail?",
            listOf(
                Attachment.File("src/A.kt"),
                Attachment.File("src/B.kt"),
                Attachment.Snippet("val x = 1\n", "kt", "src/A.kt:3"),
                Attachment.Snippet("long paste"),
            ),
        )
        assertEquals(
            "Why does this fail?\n\n" +
                "Attached files: `src/A.kt`, `src/B.kt`\n\n" +
                "From `src/A.kt:3`:\n```kt\nval x = 1\n```\n\n" +
                "Pasted text:\n```\nlong paste\n```",
            text,
        )
    }

    @Test
    fun `the fence is longer than any backticks in the code`() {
        assertEquals("```", Attachments.fence("plain"))
        assertEquals("````", Attachments.fence("a ``` b"))
        assertEquals("`````", Attachments.fence("````"))
    }

    @Test
    fun `attachments alone make a prompt`() {
        assertEquals("Attached file: `x.kt`", Attachments.compose("", listOf(Attachment.File("x.kt"))))
    }

    @Test
    fun `only long pastes are compacted`() {
        assertFalse(Attachments.isLongPaste("one line"))
        assertFalse(Attachments.isLongPaste("a\nb\nc"))
        assertTrue(Attachments.isLongPaste("x".repeat(500)))
        assertTrue(Attachments.isLongPaste((1..7).joinToString("\n") { "line $it" }))
    }

    @Test
    fun `labels count lines and preview keeps the first few`() {
        assertEquals("Pasted text · 3 lines", Attachment.Snippet("a\nb\nc\n").label)
        assertEquals("A.kt:1 · 1 line", Attachment.Snippet("a", "kt", "A.kt:1").label)
        assertEquals("A.kt", Attachment.File("src/A.kt").label)
        assertEquals("1\n2" to 3, Attachments.preview("1\n2\n3\n4\n5", 2))
        assertEquals("1\n2" to 0, Attachments.preview("1\n2", 5))
    }
}
