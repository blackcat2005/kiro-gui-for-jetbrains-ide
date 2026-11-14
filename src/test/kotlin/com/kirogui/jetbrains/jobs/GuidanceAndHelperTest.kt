package com.kirogui.jetbrains.jobs

import com.kirogui.jetbrains.acp.AcpEvent
import com.kirogui.jetbrains.acp.AcpParse
import com.kirogui.jetbrains.kiro.KiroResources
import com.kirogui.jetbrains.kiro.Scope
import com.kirogui.jetbrains.mcp.argStrings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GuidanceAndHelperTest {

    @Test
    fun `a successful summarization is a Compacted event`() {
        val update = Json.parseToJsonElement(
            """{"sessionUpdate":"session_info_update","_meta":{"kiro":{"summarization":{"status":"success","summary":{"conversationSummary":"x"}}}}}""",
        ).jsonObject
        assertEquals(AcpEvent.Compacted, AcpParse.sessionInfo(update))
        val running = Json.parseToJsonElement("""{"_meta":{"kiro":{"summarization":{"status":"in_progress"}}}}""").jsonObject
        assertTrue(AcpParse.sessionInfo(running) !is AcpEvent.Compacted)
    }

    @Test
    fun `only older copies of our own guidance are replaced`() {
        assertFalse(ToolGuidance.isOutdatedCopy(ToolGuidance.CONTENT))
        assertTrue(ToolGuidance.isOutdatedCopy(ToolGuidance.CONTENT.replace("v${ToolGuidance.VERSION} ", "v1 ")))
        assertTrue(ToolGuidance.isOutdatedCopy("---\ninclusion: always\n---\n\n# Using the JetBrains IDE tools (MCP server `kirogui`)\n"))
        assertFalse(ToolGuidance.isOutdatedCopy("# my own rules about kirogui"))
    }

    @Test
    fun `ensureToolGuidance writes, refreshes our old copy and leaves user files alone`(@TempDir home: Path) {
        val r = KiroResources(null, home)
        val file = home.resolve(".kiro/steering/${ToolGuidance.FILE_NAME}.md")
        assertTrue(r.ensureToolGuidance(Scope.GLOBAL))
        assertEquals(ToolGuidance.CONTENT, Files.readString(file))
        assertFalse(r.ensureToolGuidance(Scope.GLOBAL)) // current: nothing to do

        Files.writeString(file, ToolGuidance.CONTENT.replace("v${ToolGuidance.VERSION} ", "v1 "))
        assertTrue(r.ensureToolGuidance(Scope.GLOBAL))
        assertEquals(ToolGuidance.CONTENT, Files.readString(file))

        Files.writeString(file, "my own text")
        assertFalse(r.ensureToolGuidance(Scope.GLOBAL))
        assertEquals("my own text", Files.readString(file))
    }

    @Test
    fun `the steering text names every tool the reminder promises`() {
        for (tool in listOf("debug_run_until", "debug_task", "test_run", "bg_run", "find_references")) {
            assertTrue(tool in ToolGuidance.CONTENT, "$tool missing from the steering file")
            assertTrue(tool in ToolGuidance.REMINDER, "$tool missing from the reminder")
        }
        assertTrue(ToolGuidance.CONTENT.lines().size < 30, "keep the always-on steering short")
    }

    @Test
    fun `a reloaded prompt loses the reminder the plugin put before it`() {
        val sent = ToolGuidance.REMINDER + "\n\nthử nghiệm"
        assertEquals("thử nghiệm", ToolGuidance.withoutReminder(sent))
        assertEquals("plain", ToolGuidance.withoutReminder("plain"))
        assertEquals("[IDE reminder after compaction: x]", ToolGuidance.withoutReminder("[IDE reminder after compaction: x]"))
    }

    @Test
    fun `breakpoint lists are read leniently`() {
        fun list(json: String) = Json.parseToJsonElement(json).jsonObject.argStrings("b")
        assertEquals(listOf("A.kt:1", "B.kt:2 if x > 1"), list("""{"b":["A.kt:1","B.kt:2 if x > 1"]}"""))
        assertEquals(listOf("A.kt:1"), list("""{"b":"A.kt:1"}"""))
        assertEquals(listOf("A.kt:1", "B.kt:2"), list("""{"b":"[\"A.kt:1\",\"B.kt:2\"]"}"""))
        assertEquals(emptyList(), list("""{"b":""}"""))
        assertEquals(emptyList(), list("""{}"""))
    }

    @Test
    fun `the helper prompt carries the task, the context and the report format`() {
        val p = HelperPrompt.compose("why is total wrong", "suspect Cart.kt:40")
        assertTrue("why is total wrong" in p && "suspect Cart.kt:40" in p)
        assertTrue("debug_run_until" in p && "15 lines" in p && "action=wait" in p)
        assertFalse("WHAT IS KNOWN" in HelperPrompt.compose("x", null))
    }
}

class ConditionNamesTest {
    @Test
    fun `bare lower-case names are found, members calls strings and keywords are not`() {
        assertEquals(listOf("quantity"), ConditionNames.identifiers("quantity >= 10"))
        assertEquals(listOf("item", "limit"), ConditionNames.identifiers("item.quantity() >= limit && item.name != \"quantity\""))
        assertEquals(listOf("flag", "x"), ConditionNames.identifiers("this.total > MAX && size() == 0 && flag == null && x.y.z > 1"))
        assertEquals(emptyList(), ConditionNames.identifiers("this.total > MAX && size() == 0 && o == null".replace("o ==", "this ==")))
        assertEquals(listOf("count", "i", "name"), ConditionNames.identifiers("count > 3 && i % 2 == 0 && name.equals('count')"))
    }

    @Test
    fun `unknown names are those the file does not declare`() {
        val declared = setOf("item", "line", "total", "PriceCalculator")
        assertEquals(listOf("quantity"), ConditionNames.unknown("quantity >= 10", declared))
        assertEquals(emptyList(), ConditionNames.unknown("item.quantity() >= 10 && line > total", declared))
    }
}
