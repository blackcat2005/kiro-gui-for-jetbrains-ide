package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture

/** Streamed `session/update` notifications, reduced to what the UI cares about. */
sealed interface AcpEvent {
    data class AgentText(val text: String) : AcpEvent
    data class AgentThought(val text: String) : AcpEvent
    /** Only sent while a stored session is being reloaded (`replay`), echoing what the user said earlier. */
    data class UserText(val text: String, val replay: Boolean) : AcpEvent
    /** [locations] are the files the tool works on, as the agent wrote them. */
    data class ToolCall(val id: String, val title: String, val kind: String?, val status: String?, val locations: List<String> = emptyList()) : AcpEvent
    data class ToolCallUpdate(val id: String, val title: String?, val status: String?, val locations: List<String> = emptyList()) : AcpEvent
    data class Plan(val entries: List<String>) : AcpEvent
    /** `_kiro.dev/metadata`: context window usage and, per turn, credits spent. */
    data class Metadata(val contextPercent: Double?, val credits: Double?) : AcpEvent
    data class Commands(val commands: List<SlashCommand>) : AcpEvent
    /** Something Kiro wants the user to see, such as an MCP server that failed to connect. */
    data class DisplayError(val message: String) : AcpEvent
    /**
     * The v3 engine sends the model list (and effort, when there is one) after `session/new` has answered, as a
     * `config_option_update`; the answer itself carries only the agents.
     */
    data class ConfigOptions(
        val models: List<Choice>,
        val currentModelId: String?,
        val efforts: List<Choice>,
        val currentEffortId: String?,
        val effortConfigId: String?,
    ) : AcpEvent

    /** Kiro summarised the conversation (`/compact` or automatically); what the chat said before is now a summary. */
    data object Compacted : AcpEvent
    data class Other(val type: String) : AcpEvent
    data class Closed(val reason: String?) : AcpEvent
}

data class SlashCommand(val name: String, val description: String, val subcommands: List<String>)

/** A selectable agent (ACP "mode") or model. */
data class Choice(val id: String, val name: String, val description: String? = null) {
    /** What a list's speed search matches against. */
    override fun toString(): String = name
}

data class SessionInfo(
    val sessionId: String,
    val modes: List<Choice>,    val currentModeId: String?,
    val models: List<Choice>,
    val currentModelId: String?,
    /** True when the models came from the v3 `model` config option; they are then changed with `session/set_config_option`. */
    val modelViaConfig: Boolean = false,
    /** A reasoning-effort style config option, when kiro-cli offers one; empty otherwise. */
    val efforts: List<Choice> = emptyList(),
    val currentEffortId: String? = null,
    /** The `configId` to pass to `session/set_config_option` for [efforts]. */
    val effortConfigId: String? = null,
)

data class PermissionOption(val id: String, val name: String, val kind: String?)

data class PermissionRequest(
    val toolTitle: String,
    val options: List<PermissionOption>,
    /** The tool's arguments as the agent sent them (path, content, oldStr/newStr, shell command, ...). */
    val rawInput: JsonObject? = null,
    /** The session asking; a helper session the IDE runs itself is answered without bothering the user. */
    val sessionId: String? = null,
)

/** Resolve with the chosen option id, or null when the user dismissed the prompt (= cancelled). */
fun interface PermissionHandler {
    fun request(request: PermissionRequest): CompletableFuture<String?>
}

/** File access the agent may request (the fs methods). The IDE implementation prefers unsaved editor buffers. */
interface FileAccess {
    fun readText(path: String): String
    fun writeText(path: String, content: String)
}

/** `line` is 1-based per the ACP spec. */
fun sliceLines(text: String, line: Int?, limit: Int?): String {
    if (line == null && limit == null) return text
    val lines = text.split("\n")
    val from = ((line ?: 1) - 1).coerceIn(0, lines.size)
    val to = if (limit == null) lines.size else (from + limit).coerceAtMost(lines.size)
    return lines.subList(from, to).joinToString("\n")
}
