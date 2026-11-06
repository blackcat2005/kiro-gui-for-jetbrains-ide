package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The same UI-facing results whichever engine (v2 or v3) kiro-cli answered with. Fixtures are trimmed real replies. */
class AcpParseTest {

    private fun obj(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `v2 session lists models under models`() {
        val session = AcpParse.session(
            obj(
                """{"sessionId":"s","modes":{"currentModeId":"kiro_default","availableModes":[{"id":"kiro_default","name":"kiro_default"}]},
                   "models":{"currentModelId":"auto","availableModels":[
                     {"modelId":"auto","name":"auto","description":"picks one"},{"modelId":"claude-haiku-4.5","name":"claude-haiku-4.5"}]}}""",
            ),
            "s",
        )
        assertEquals(listOf("auto", "claude-haiku-4.5"), session.models.map { it.id })
        assertEquals("auto", session.currentModelId)
        assertFalse(session.modelViaConfig)
        assertEquals("kiro_default", session.currentModeId)
    }

    @Test
    fun `v3 session lists models as the model config option`() {
        val session = AcpParse.session(
            obj(
                """{"sessionId":"s","modes":{"currentModeId":"vibe","availableModes":[{"id":"vibe","name":"Default"},{"id":"lean","name":"lean"}]},
                   "configOptions":[
                     {"type":"select","id":"mode","name":"Mode","category":"mode","currentValue":"vibe","options":[{"value":"vibe","name":"Default"}]},
                     {"type":"select","id":"model","name":"Model","category":"model","currentValue":"auto","options":[
                        {"value":"auto","name":"auto","description":"picks one"},{"value":"glm-5","name":"glm-5"}]},
                     {"type":"select","id":"autopilot","currentValue":"on","options":[{"value":"on","name":"on"}]}]}""",
            ),
            "s",
        )
        assertEquals(listOf("auto", "glm-5"), session.models.map { it.id })
        assertEquals("auto", session.currentModelId)
        assertTrue(session.modelViaConfig)
        assertEquals(listOf("vibe", "lean"), session.modes.map { it.id })
    }

    @Test
    fun `a session with no model information still parses`() {
        val session = AcpParse.session(obj("""{"sessionId":"s"}"""), "s")
        assertTrue(session.models.isEmpty())
        assertNull(session.currentModelId)
        assertFalse(session.modelViaConfig)
    }

    @Test
    fun `v3 context usage and turn credits become metadata`() {
        val context = AcpParse.sessionInfo(
            obj("""{"sessionUpdate":"session_info_update","_meta":{"kiro":{"contextUsage":{"usagePercentage":12.5},"kind":"context_usage"}}}"""),
        )
        assertEquals(AcpEvent.Metadata(12.5, null), context)

        val withBreakdown = AcpParse.sessionInfo(
            obj("""{"sessionUpdate":"session_info_update","_meta":{"kiro":{"contextUsage":{"usagePercentage":3.0,"summarizationThreshold":80},"breakdown":{}}}}"""),
        )
        assertEquals(AcpEvent.Metadata(3.0, null), withBreakdown)

        val turn = AcpParse.sessionInfo(
            obj(
                """{"sessionUpdate":"session_info_update","_meta":{"kiro":{"kind":"turn_completion",
                   "promptTurnSummaries":[{"unit":"credit","usage":0.25},{"unit":"credit","usage":0.5}]}}}""",
            ),
        )
        assertEquals(AcpEvent.Metadata(null, 0.75), turn)
    }

    @Test
    fun `session usage totals are not counted again and other info updates are ignored`() {
        // The per-turn figure is already added; adding the running total as well would double count.
        assertNull(AcpParse.sessionInfo(obj("""{"_meta":{"kiro":{"kind":"session_usage","credits":[{"usage":1.0}]}}}""")))
        assertNull(AcpParse.sessionInfo(obj("""{"_meta":{"kiro":{"kind":"turn_start"}}}""")))
        assertNull(AcpParse.sessionInfo(obj("""{"title":"x"}""")))
    }

    @Test
    fun `display errors are surfaced`() {
        val event = AcpParse.sessionInfo(
            obj(
                """{"_meta":{"kiro":{"kind":"display_error","message":"MCP server \"kb\" failed to connect",
                   "displayError":{"message":"MCP server \"kb\" failed to connect","errorType":"mcp_connection_error"}}}}""",
            ),
        )
        assertEquals(AcpEvent.DisplayError("MCP server \"kb\" failed to connect"), event)
    }

    @Test
    fun `v3 available commands are typed as slash commands`() {
        val commands = AcpParse.commands(
            obj(
                """{"sessionUpdate":"available_commands_update","availableCommands":[
                   {"name":"context-gatherer","description":"Investigates the codebase","input":{"hint":"task to delegate"}},
                   {"name":"/already","description":""},{"description":"no name"}]}""",
            ),
        )
        assertEquals(listOf("/context-gatherer", "/already"), commands.map { it.name })
        assertEquals("Investigates the codebase", commands.first().description)
    }
}
