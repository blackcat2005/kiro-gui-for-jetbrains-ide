package com.kirogui.jetbrains.kiro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import kotlin.io.path.exists

/** One visible thing in a stored conversation. */
sealed interface PreviewItem {
    data class User(val text: String) : PreviewItem
    data class Assistant(val text: String) : PreviewItem
    data class Tool(val title: String) : PreviewItem
}

/**
 * Reads a stored session's conversation straight from Kiro's files, so it can be shown the moment you click it
 * instead of waiting for the agent process to reload it. Handles both stores:
 *  - legacy `<id>.jsonl`: `Prompt` and `AssistantMessage` records (text parts and tool uses; reasoning is skipped);
 *  - v3 `messages.jsonl`: `user`, `assistant` (operationType "Say" only) and `tool_call` payloads.
 * Anything it can't understand is skipped; it never throws for a malformed line.
 */
object TranscriptReader {

    fun read(session: KiroSession, maxItems: Int = MAX_ITEMS): List<PreviewItem> {
        val file = when (session.store) {
            SessionStore.LEGACY -> session.metaFile.resolveSibling("${session.id}.jsonl")
            SessionStore.V3 -> session.metaFile.resolveSibling("messages.jsonl")
        }
        if (!file.exists()) return emptyList()
        val items = ArrayList<PreviewItem>()
        try {
            Files.newBufferedReader(file).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val obj = parse(line) ?: continue
                    when (session.store) {
                        SessionStore.LEGACY -> legacy(obj, items)
                        SessionStore.V3 -> v3(obj, items)
                    }
                }
            }
        } catch (_: IOException) {
            // return what was read so far
        }
        // Long sessions: keep the most recent part, which is what you came back for.
        return if (items.size > maxItems) items.takeLast(maxItems) else items
    }

    private fun legacy(o: JsonObject, out: MutableList<PreviewItem>) {
        val data = o["data"] as? JsonObject ?: return
        val content = data["content"] as? JsonArray ?: return
        when (str(o, "kind")) {
            "Prompt" -> {
                val text = content.mapNotNull { (it as? JsonObject)?.takeIf { c -> str(c, "kind") == "text" }?.let { c -> str(c, "data") } }
                    .joinToString("\n").trim()
                if (text.isNotEmpty()) out.add(PreviewItem.User(text))
            }
            "AssistantMessage" -> content.forEach { part ->
                val c = part as? JsonObject ?: return@forEach
                when (str(c, "kind")) {
                    "text" -> str(c, "data")?.trim()?.takeIf { it.isNotEmpty() }?.let { addAssistant(out, it) }
                    "toolUse" -> {
                        val d = c["data"] as? JsonObject ?: return@forEach
                        val purpose = ((d["input"] as? JsonObject)?.let { str(it, "__tool_use_purpose") })
                        out.add(PreviewItem.Tool(purpose?.takeIf { it.isNotBlank() } ?: str(d, "name") ?: "tool"))
                    }
                }
            }
        }
    }

    private fun v3(o: JsonObject, out: MutableList<PreviewItem>) {
        val p = o["payload"] as? JsonObject ?: return
        when (str(p, "type")) {
            "user" -> str(p, "content")?.trim()?.takeIf { it.isNotEmpty() }?.let { out.add(PreviewItem.User(it)) }
            "assistant" -> if (str(p, "operationType") == "Say") {
                str(p, "content")?.trim()?.takeIf { it.isNotEmpty() && it != "..." }?.let { addAssistant(out, it) }
            }
            "tool_call" -> out.add(PreviewItem.Tool(str(p, "title") ?: str(p, "toolName") ?: "tool"))
        }
    }

    /** Consecutive assistant texts (split around tool calls in the file) read better as one message. */
    private fun addAssistant(out: MutableList<PreviewItem>, text: String) {
        val last = out.lastOrNull()
        if (last is PreviewItem.Assistant) out[out.lastIndex] = PreviewItem.Assistant(last.text + "\n\n" + text)
        else out.add(PreviewItem.Assistant(text))
    }

    private fun parse(line: String): JsonObject? = try {
        Json.parseToJsonElement(line).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun str(o: JsonObject, key: String): String? = o[key]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull }

    private const val MAX_ITEMS = 600
}
