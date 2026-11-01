package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.kiro.FileLinker
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JEditorPane
import javax.swing.text.html.HTMLEditorKit
import kotlin.test.assertTrue

/** Renders the transcript to PNGs (light and dark) so the layout can be looked at without launching an IDE. */
class TranscriptRenderTest {

    private val light = Palette("1F1F1F", "6E6E6E", "F0F2F5", "EAECEF", "2F6FED", "2E7D32", "C62828", SyntaxColors.LIGHT)
    private val dark = Palette("DFE1E5", "8C8F94", "2F3136", "26282C", "6BA4FF", "62B543", "FF6B68", SyntaxColors.DARK)

    private val known = setOf("LoginService.kt", "src/test/LoginTest.kt", "src/main/LoginService.kt")
    private val linker = FileLinker { raw -> raw.takeIf { it in known } }

    private val answer = """
        ## Why the login test fails

        The test **fails on CI** because the clock is not injected. It is in `LoginService.kt:42` and the test
        `src/test/LoginTest.kt` calls it. See src/main/LoginService.kt:12-20 too.

        1. `LoginService` reads `Instant.now()` directly
        2. CI runs in UTC, your machine does not

        > Tests that depend on the wall clock are flaky by design.

        Fix:

        ```kotlin
        // inject the clock
        class LoginService(private val clock: Clock = Clock.systemUTC()) {
            @Throws(IllegalStateException::class)
            fun expired(t: Instant): Boolean = t.isBefore(clock.instant().minusSeconds(30))
        }
        ```

        Config and the change as a diff:

        ```json
        {"retries": 3, "clock": "utc", "strict": true}
        ```

        ```diff
        @@ -10,3 +10,3 @@
        -    val now = Instant.now()
        +    val now = clock.instant()
        ```

        | check | result |
        |-------|--------|
        | unit  | pass   |
        | CI    | fail   |
    """.trimIndent()

    private fun sample(): List<ChatBlock> = listOf(
        ChatBlock.User("Why does the login test fail?\nIt only happens on CI."),
        ChatBlock.Tool("t1", "Reading LoginTest.kt", "completed"),
        ChatBlock.Tool("t2", "Running ./gradlew test --tests LoginTest", "failed"),
        ChatBlock.Tool("t3", "Editing LoginService.kt", "in_progress"),
        ChatBlock.Agent(StringBuilder(answer)),
        ChatBlock.Note("Stopped: cancelled"),
    )

    private fun paint(blocks: List<ChatBlock>, p: Palette, bg: Color, file: String) {
        val pane = JEditorPane().apply {
            contentType = "text/html"
            editorKit = HTMLEditorKit().also {
                it.styleSheet.addRule("body { font-family: Segoe UI, sans-serif; font-size: 13pt; }")
            }
            background = bg
            text = TranscriptHtml.render(blocks, p, linker)
            setSize(480, 10)
            setSize(480, preferredSize.height.coerceAtLeast(120))
        }
        val img = BufferedImage(pane.width, pane.height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        g.color = bg
        g.fillRect(0, 0, img.width, img.height)
        pane.paint(g)
        g.dispose()
        val out = File(System.getProperty("transcript.out") ?: return, file)
        out.parentFile.mkdirs()
        ImageIO.write(img, "png", out)
    }

    @Test
    fun `renders light, dark and empty states`() {
        paint(sample(), light, Color.WHITE, "transcript-light.png")
        paint(sample(), dark, Color(0x2B, 0x2D, 0x30), "transcript-dark.png")
        paint(emptyList(), light, Color.WHITE, "transcript-empty.png")

        val html = TranscriptHtml.render(sample(), light, linker)
        assertTrue(html.contains("href=\"copy:4\""), "copy link points at the agent block index")
        assertTrue(html.contains("href=\"copycode:4:0\"") && html.contains("href=\"copycode:4:2\""), "each code block has a Copy link")
        assertTrue(html.contains("bgcolor=\"#EAECEF\"") && !html.contains("<pre>"), "code is in a shaded cell, no raw <pre>")
        assertTrue(html.contains("<font color=\"#0033B3\">class</font>"), "kotlin keyword is coloured")
        assertTrue(html.contains("kirofile:"), "file names are links")
        assertTrue(html.contains("<table border=\"1\" bordercolor="), "markdown tables get borders")
        assertTrue(html.contains("&#10007;") && html.contains("&#10003;"), "tool status marks")
    }

    @Test
    fun `finished answers are rendered once`() {
        val agent = ChatBlock.Agent(StringBuilder(answer))
        val first = TranscriptHtml.render(listOf(agent), light, linker)
        val cached = agent.cachedHtml
        val second = TranscriptHtml.render(listOf(agent), light, linker)
        assertTrue(first == second && agent.cachedHtml === cached, "same instance reused while nothing changed")
        agent.text.append("\nmore")
        TranscriptHtml.render(listOf(agent), light, linker)
        assertTrue(agent.cachedHtml !== cached, "growing text re-renders")
        TranscriptHtml.render(listOf(agent), dark, linker)
        assertTrue(agent.cachedHtml?.contains("CF8E6D") == true, "a theme change re-renders")
    }
}
