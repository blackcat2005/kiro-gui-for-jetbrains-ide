package com.kirogui.jetbrains.kiro

import com.kirogui.jetbrains.acp.SlashCommand
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalCommandsTest {
    private val none = emptyList<SlashCommand>()

    @Test
    fun `the chat runs its commands, with the argument split off`() {
        assertEquals(LocalCommands.Call("/compact", ""), LocalCommands.parse("/compact", none))
        assertEquals(LocalCommands.Call("/model", "haiku"), LocalCommands.parse("  /MODEL  haiku ", none))
        assertEquals(LocalCommands.Call("/autopilot", "off"), LocalCommands.parse("/autopilot off", none))
        assertNull(LocalCommands.parse("/unknown", none))
        assertNull(LocalCommands.parse("explain /compact", none))
        assertNull(LocalCommands.parse("/model a\nb", none), "a multi-line message is a prompt")
    }

    @Test
    fun `commands Kiro advertises itself are left to Kiro`() {
        val kiro = listOf(SlashCommand("/compact", "x", emptyList()), SlashCommand("/usage", "y", emptyList()))
        assertNull(LocalCommands.parse("/compact", kiro))
        assertEquals(false, LocalCommands.available(kiro).any { it.name == "/compact" || it.name == "/usage" })
        assertEquals(LocalCommands.Call("/new", ""), LocalCommands.parse("/new", kiro))
    }

    @Test
    fun `match prefers an exact name, else everything that contains the query`() {
        val items = listOf("claude-haiku-4.5", "claude-sonnet-4.5", "claude-sonnet-4", "auto")
        fun m(q: String) = LocalCommands.match(items, q, { it }, { it })
        assertEquals(listOf("claude-sonnet-4"), m("claude-sonnet-4"))
        assertEquals(listOf("claude-haiku-4.5"), m("haiku"))
        assertEquals(listOf("claude-sonnet-4.5", "claude-sonnet-4"), m("sonnet"))
        assertEquals(emptyList(), m("opus"))
    }
}
