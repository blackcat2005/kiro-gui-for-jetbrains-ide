package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.ui.DiffText.Kind
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class DiffTextTest {

    private val diff = """
        diff --git a/src/A.kt b/src/A.kt
        --- a/src/A.kt
        +++ b/src/A.kt
        @@ -1,4 +1,4 @@
         fun a() {
        -    old()
        +    new()
        +    extra()
         }
    """.trimIndent()

    @Test
    fun `lines are typed and markers removed`() {
        val lines = DiffText.parse(diff)
        assertEquals(
            listOf(Kind.HEADER, Kind.HEADER, Kind.HEADER, Kind.HUNK, Kind.CONTEXT, Kind.DEL, Kind.ADD, Kind.ADD, Kind.CONTEXT),
            lines.map { it.kind },
        )
        assertEquals("    old()", lines[5].text)
        assertEquals(2 to 1, DiffText.stats(lines))
        assertEquals("src/A.kt", DiffText.path(lines))
    }

    @Test
    fun `new and old side give pasteable code`() {
        assertEquals("fun a() {\n    new()\n    extra()\n}", DiffText.newSide(diff))
        assertEquals("fun a() {\n    old()\n}", DiffText.oldSide(diff))
    }

    @Test
    fun `a removed line that looks like a header is still a change`() {
        val lines = DiffText.parse("@@ -1 +1 @@\n--- not a header\n+++ nor this")
        assertEquals(listOf(Kind.HUNK, Kind.DEL, Kind.ADD), lines.map { it.kind })
    }

    @Test
    fun `a bare diff without headers still works`() {
        val lines = DiffText.parse("-a\n+b")
        assertEquals(listOf(Kind.DEL, Kind.ADD), lines.map { it.kind })
        assertEquals(null, DiffText.path(lines))
    }
}
