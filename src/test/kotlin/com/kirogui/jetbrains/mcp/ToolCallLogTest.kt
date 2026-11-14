package com.kirogui.jetbrains.mcp

import com.kirogui.jetbrains.jobs.ToolFilter
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ToolCallLogTest {
    private fun call(tool: String, n: Int = 0) = ToolCall(1_700_000_000_000L + n, tool, "s1", "{}", true, "done $n", 5)

    @Test fun `keeps the newest calls up to the capacity, newest first`() {
        val log = ToolCallLog(3)
        (1..5).forEach { log.record(call("t$it", it)) }
        assertEquals(listOf("t5", "t4", "t3"), log.snapshot().map { it.tool })
    }

    @Test fun `clear empties it and tells listeners`() {
        val log = ToolCallLog()
        var told = 0
        log.addListener { told++ }
        log.record(call("a"))
        log.clear()
        assertTrue(log.snapshot().isEmpty())
        assertEquals(2, told)
    }

    @Test fun `long results are trimmed and json export has the fields`() {
        val log = ToolCallLog()
        log.record(ToolCall(0, "eval", null, "{}", false, "x".repeat(20_000), 7))
        val saved = log.snapshot().single()
        assertEquals(ToolCallLog.MAX_RESULT, saved.result.length)
        val json = ToolCallLog.toJson(log.snapshot())
        assertTrue("\"tool\": \"eval\"" in json && "\"status\": \"error\"" in json && "\"durationMs\": 7" in json)
    }

    @Test fun `only loopback or missing origins are allowed`() {
        assertTrue(McpServer.originAllowed(null))
        assertTrue(McpServer.originAllowed(""))
        assertTrue(McpServer.originAllowed("http://localhost:3000"))
        assertTrue(McpServer.originAllowed("http://127.0.0.1:8080"))
        assertFalse(McpServer.originAllowed("https://evil.example"))
        assertFalse(McpServer.originAllowed("null"))
        assertFalse(McpServer.originAllowed("http://localhost.evil.example"))
    }

    @Test fun `tool filter splits on commas and whitespace`() {
        assertEquals(setOf("debug_eval", "bg_run", "x"), ToolFilter.parse("debug_eval, bg_run\nx ; "))
        assertTrue(ToolFilter.parse("  ").isEmpty())
    }
}
