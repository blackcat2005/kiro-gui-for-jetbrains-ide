package com.kirogui.jetbrains.kiro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The v3 engine's edit tools carry no `command`; the diff review must still understand them. */
class EditPreviewV3Test {
    private val cwd = Path.of("/proj")
    private fun raw(json: String) = Json.parseToJsonElement(json).jsonObject
    private val files = mapOf(Path.of("/proj/hello.txt") to "line one\nline two\nline two\n")
    private fun read(p: Path): String? = files[p]

    @Test
    fun `str_replace without a command is a replace`() {
        val c = EditPreview.compute(raw("""{"path":"hello.txt","oldStr":"line two","newStr":"line 2","replace_all":false}"""), cwd, ::read)!!
        assertEquals("line one\nline 2\nline two\n", c.after)
        assertEquals("line one\nline 2\nline 2\n", EditPreview.compute(raw("""{"path":"hello.txt","oldStr":"line two","newStr":"line 2","replace_all":true}"""), cwd, ::read)!!.after)
    }

    @Test
    fun `fs_write with text creates or overwrites the file`() {
        val created = EditPreview.compute(raw("""{"path":"new.txt","text":"hi"}"""), cwd, ::read)!!
        assertTrue(created.isNewFile)
        assertEquals("hi", created.after)
        val overwritten = EditPreview.compute(raw("""{"path":"hello.txt","text":"x"}"""), cwd, ::read)!!
        assertEquals("line one\nline two\nline two\n", overwritten.before)
        assertEquals("x", overwritten.after)
    }

    @Test
    fun `other tools are left alone`() {
        assertNull(EditPreview.compute(raw("""{"path":"hello.txt","offset":null,"limit":null}"""), cwd, ::read))
        assertNull(EditPreview.compute(raw("""{"command":"ls"}"""), cwd, ::read))
    }
}
