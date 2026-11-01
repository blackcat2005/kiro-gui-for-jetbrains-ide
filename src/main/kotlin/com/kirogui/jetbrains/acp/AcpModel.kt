package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.JsonObject
import java.util.concurrent.CompletableFuture

/** Streamed `session/update` notifications, reduced to what the UI cares about. */
sealed interface AcpEvent {
    data class AgentText(val text: String) : AcpEvent
    data class AgentThought(val text: String) : AcpEvent
    /** Only sent while a stored session is being reloaded (`replay`), echoing what the user said earlier. */
    data class UserText(val text: String, val replay: Boolean) : AcpEvent
    data class ToolCall(val id: String, val title: String, val kind: String?, val status: String?) : AcpEvent
    data class ToolCallUpdate(val id: String, val title: String?, val status: String?) : AcpEvent
    data class Plan(val entries: List<String>) : AcpEvent
    /** `_kiro.dev/metadata`: context window usage and, per turn, credits spent. */
    data class Metadata(val contextPercent: Double?, val credits: Double?) : AcpEvent
    data class Commands(val commands: List<SlashCommand>) : AcpEvent
    data class Other(val type: String) : AcpEvent
    data class Closed(val reason: String?) : AcpEvent
}

data class SlashCommand(val name: String, val description: String, val subcommands: List<String>)

/** A selectable agent (ACP "mode") or model. */
data class Choice(val id: String, val name: String, val description: String? = null)

data class SessionInfo(
    val sessionId: String,
    val modes: List<Choice>,
    val currentModeId: String?,
    val models: List<Choice>,
    val currentModelId: String?,
)

data class PermissionOption(val id: String, val name: String, val kind: String?)

data class PermissionRequest(
    val toolTitle: String,
    val options: List<PermissionOption>,
    /** The tool's arguments as the agent sent them (path, content, oldStr/newStr, shell command, ...). */
    val rawInput: JsonObject? = null,
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
