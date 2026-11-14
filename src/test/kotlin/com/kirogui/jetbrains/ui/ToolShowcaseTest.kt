package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.jobs.ToolShowcase
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ToolShowcaseTest {
    private val base = Palette("1F1F1F", "6E6E6E", "F0F2F5", "EAECEF", "2F6FED", "2E7D32", "C62828", SyntaxColors.LIGHT)

    @Test
    fun `only cards with a tool that is on are shown, listing just the tools that are on`() {
        val cards = ToolShowcase.available(setOf("test_run", "debug_eval", "bg_run"))
        assertEquals(listOf("Run your tests", "Debug for real", "Long jobs in the background"), cards.map { it.title })
        assertEquals(listOf("debug_eval"), cards[1].tools)
        assertTrue(ToolShowcase.available(emptySet()).isEmpty())
    }

    @Test
    fun `every tool the plugin serves is on some card`() {
        val served = setOf(
            "bg_run", "job_status", "job_stop", "test_run", "debug_start", "debug_run_until", "debug_breakpoint", "debug_exceptions",
            "debug_state", "debug_eval", "debug_control", "debug_task", "run_logs", "project_modules", "file_structure",
            "find_definition", "find_references", "find_implementations", "call_graph", "find_symbol", "editor_state",
        )
        val shown = ToolShowcase.ALL.flatMap { it.tools }.toSet()
        assertEquals(emptySet(), served - shown, "tools no card mentions")
    }

    @Test
    fun `the empty chat lists the cards in both renderers, a full chat does not`() {
        val p = base.copy(tools = ToolShowcase.available(ToolShowcase.ALL.flatMap { it.tools }.toSet()))
        for (html in listOf(WebHtml.render(emptyList(), p), TranscriptHtml.render(emptyList(), p))) {
            p.tools.indices.forEach { assertTrue(html.contains("href=\"tool:$it\""), "card $it") }
            assertTrue(html.contains("Debug on a budget") && html.contains("debug_task"))
        }
        assertFalse(WebHtml.render(emptyList(), base).contains("tool:0"), "no tools, no cards")
        assertFalse(TranscriptHtml.render(emptyList(), base).contains("tool:0"))
    }

    @Test
    fun `the guidance banner offers the install, or says it is done`() {
        val cards = ToolShowcase.available(ToolShowcase.ALL.flatMap { it.tools }.toSet())
        for (render in listOf<(Palette) -> String>({ WebHtml.render(emptyList(), it) }, { TranscriptHtml.render(emptyList(), it) })) {
            val missing = render(base.copy(tools = cards, guidance = false))
            assertTrue(missing.contains("guidance:install") && missing.contains("Install Kiro guidance for all Kiro GUI tools"))
            val done = render(base.copy(tools = cards, guidance = true))
            assertFalse(done.contains("guidance:install"))
            assertTrue(done.contains("Kiro has been told to use these tools"))
            assertFalse(render(base.copy(tools = cards, guidance = null)).contains("guidance:install"), "unknown: say nothing")
        }
    }
}
