package com.kirogui.jetbrains.ui

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeHighlighterTest {

    private val c = SyntaxColors.LIGHT
    private fun hl(code: String, lang: String?) = CodeHighlighter.highlight(code, lang, c)
    private fun font(color: String, text: String) = "<font color=\"#$color\">$text</font>"

    @Test
    fun `kotlin keywords, types, calls, strings, numbers and comments`() {
        val html = hl("class LoginService(val clock: Clock) {\n  fun expired(t: Instant) = t.isBefore(clock.instant()) // done\n  val n = 42\n  val s = \"hi\"\n}", "kotlin")
        assertTrue(html.contains(font(c.keyword, "class")))
        assertTrue(html.contains(font(c.type, "LoginService")))
        assertTrue(html.contains(font(c.type, "Clock")))
        assertTrue(html.contains(font(c.keyword, "fun")))
        assertTrue(html.contains(font(c.function, "expired")))
        assertTrue(html.contains(font(c.function, "isBefore")))
        assertTrue(html.contains(font(c.number, "42")))
        assertTrue(html.contains(font(c.string, "\"hi\"")))
        assertTrue(html.contains("<font color=\"#${c.comment}\"><i>// done</i></font>"))
    }

    @Test
    fun `fence aliases map to the same language`() {
        val code = "def f(self): return None"
        assertEquals(hl(code, "python"), hl(code, "py"))
        assertEquals(hl("val x = 1", "kotlin"), hl("val x = 1", "kt"))
        assertEquals(hl("const a = 1", "js"), hl("const a = 1", "javascript"))
        assertTrue(hl(code, "py").contains(font(c.keyword, "def")))
        assertTrue(hl(code, "py").contains(font(c.keyword, "None")))
    }

    @Test
    fun `source is HTML-escaped and cannot inject markup`() {
        val html = hl("val s = \"a<b>&c\"; // </font><script>", "kotlin")
        assertFalse(html.contains("<script>"))
        assertTrue(html.contains("&lt;b&gt;&amp;c"))
        assertTrue(html.contains("&lt;/font&gt;&lt;script&gt;"))
        assertEquals("a &lt; b &amp;&amp; c", hl("a < b && c", null))
    }

    @Test
    fun `unknown or missing language is plain escaped text`() {
        assertEquals("fun <x>".replace("<", "&lt;").replace(">", "&gt;"), hl("fun <x>", "brainfuck"))
        assertEquals("plain", hl("plain", null))
    }

    @Test
    fun `json keys differ from values`() {
        val html = hl("{\"name\": \"kiro\", \"n\": 3, \"ok\": true}", "json")
        assertTrue(html.contains(font(c.type, "\"name\"")), "key")
        assertTrue(html.contains(font(c.string, "\"kiro\"")), "value")
        assertTrue(html.contains(font(c.number, "3")))
        assertTrue(html.contains(font(c.keyword, "true")))
    }

    @Test
    fun `an unterminated string stops at the end of the line`() {
        val html = hl("val a = \"oops\nval b = 1", "kotlin")
        assertTrue(html.contains(font(c.string, "\"oops")))
        assertTrue(html.contains(font(c.number, "1")), "later lines are still highlighted")
    }

    @Test
    fun `triple quoted strings and python comments`() {
        val html = hl("\"\"\"doc\nstring\"\"\"\n# note\nx = 1", "python")
        assertTrue(html.contains(font(c.string, "\"\"\"doc\nstring\"\"\"")))
        assertTrue(html.contains("<i># note</i>"))
    }

    @Test
    fun `diff lines are coloured whole`() {
        val html = hl("--- a/x.kt\n+++ b/x.kt\n@@ -1,2 +1,2 @@\n-old\n+new\n same", "diff")
        assertTrue(html.contains("<font color=\"#${c.added}\">+new</font>"))
        assertTrue(html.contains("<font color=\"#${c.removed}\">-old</font>"))
        assertTrue(html.contains("<font color=\"#${c.keyword}\">@@ -1,2 +1,2 @@</font>"))
        assertTrue(html.contains("<i>--- a/x.kt</i>"), "file headers are not mistaken for removals")
        assertTrue(html.endsWith(" same"))
    }

    @Test
    fun `xml tags, attributes and values`() {
        val html = hl("<!-- c --><dependency scope=\"test\">x</dependency>", "xml")
        assertTrue(html.contains("<i>&lt;!-- c --&gt;</i>"))
        assertTrue(html.contains(font(c.keyword, "dependency")))
        assertTrue(html.contains(font(c.function, "scope")))
        assertTrue(html.contains(font(c.string, "\"test\"")))
    }

    @Test
    fun `yaml keys and sql keywords in any case`() {
        assertTrue(hl("name: kiro\nport: 80", "yaml").contains(font(c.function, "name")))
        assertTrue(hl("SELECT id FROM users", "sql").contains(font(c.keyword, "SELECT")))
        assertTrue(hl("select id from users", "sql").contains(font(c.keyword, "from")))
    }
}
