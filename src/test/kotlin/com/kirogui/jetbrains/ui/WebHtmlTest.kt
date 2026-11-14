package com.kirogui.jetbrains.ui

import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WebHtmlTest {
    private val p = Palette("DFE1E5", "8C8F94", "2F3136", "26282C", "B591FF", "62B543", "FF6B68", SyntaxColors.DARK, bg = "1E1F22")

    @Test fun `empty chat has no starter chips or greeting`() {
        val html = WebHtml.render(emptyList(), p)
        assertTrue("suggest:" !in html && "What can I help with" !in html)
    }

    @Test fun `messages, answer with code, tools and job news are rendered with the link schemes the chat handles`() {
        val agent = ChatBlock.Agent(StringBuilder("Here:\n\n```kotlin\nval x = 1\n```\n\nSee `LoginService.kt`."))
        val html = WebHtml.render(
            listOf(
                ChatBlock.User("hello <b>", time = 1_700_000_000_000L),
                agent,
                ChatBlock.Tool("1", "Running: @kirogui/bg_run", "completed"),
                ChatBlock.Note("⚙ Background job #1 (t.ps1) finished, exit code 1"),
            ),
            p,
        )
        assertTrue("hello &lt;b&gt;" in html, "user text is escaped")
        assertTrue("href=\"copy:1\"" in html && "href=\"copycode:1:0\"" in html, "copy links keep their index")
        assertTrue("class=\"code\"" in html && "<pre>" in html, "code block is a card")
        assertFalse("@kirogui/" in html, "server prefix is hidden")
        assertTrue("class=\"ok\"" in html && "var(--err)" in html, "tool done, job failed")
    }

    @Test fun `css carries the palette and the page background`() {
        val css = WebHtml.css(p)
        assertTrue("--accent:#B591FF" in css && "--bg:#1E1F22" in css && "border-radius" in css)
    }
}
