package com.kirogui.jetbrains.kiro

import com.kirogui.jetbrains.acp.SlashCommand

/**
 * Slash commands the chat runs itself, because the engine (kiro-cli 3.x) does not list or run them: typed as a prompt
 * they would only be text for the model. Ones Kiro advertises itself (the v2 engine does) are left to Kiro.
 */
object LocalCommands {
    val ALL = listOf(
        SlashCommand("/compact", "Summarise the conversation to free context (runs in the IDE)", emptyList()),
        SlashCommand("/new", "Start a new chat", emptyList()),
        SlashCommand("/model", "Show or pick the model: /model <part of its name>", emptyList()),
        SlashCommand("/agent", "Show or pick the agent: /agent <part of its name>", emptyList()),
        SlashCommand("/trust", "Approve every permission request without asking (like --trust-all-tools): on or off", listOf("on", "off")),
        SlashCommand("/autopilot", "Let Kiro run tools without asking: on or off", listOf("on", "off")),
        SlashCommand("/tools", "Show the IDE tools Kiro can use", emptyList()),
        SlashCommand("/usage", "Show plan credits and context use", emptyList()),
        SlashCommand("/help", "List the commands the chat runs itself", emptyList()),
    )

    /** The commands to offer and run, given what [kiro] already advertises. */
    fun available(kiro: List<SlashCommand>): List<SlashCommand> =
        ALL.filter { local -> kiro.none { it.name.equals(local.name, ignoreCase = true) } }

    data class Call(val name: String, val arg: String)

    /** [text] as a local command, or null when it is not one (then it goes to Kiro as usual). */
    fun parse(text: String, kiro: List<SlashCommand>): Call? {
        val t = text.trim()
        if (!t.startsWith("/") || '\n' in t) return null
        val name = t.substringBefore(' ').lowercase()
        val arg = t.substringAfter(' ', "").trim()
        return if (available(kiro).any { it.name == name }) Call(name, arg) else null
    }

    /** The choices whose id or name matches [query]: an exact one wins, else every one that contains it. */
    fun <T> match(items: List<T>, query: String, idOf: (T) -> String, nameOf: (T) -> String): List<T> {
        val q = query.trim()
        items.filter { idOf(it).equals(q, true) || nameOf(it).equals(q, true) }.let { if (it.size == 1) return it }
        return items.filter { idOf(it).contains(q, true) || nameOf(it).contains(q, true) }
    }
}
