package com.kirogui.jetbrains.mcp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** One call Kiro made to an IDE tool, kept for the "Tool calls" history. */
class ToolCall(
    val time: Long,
    val tool: String,
    val sessionId: String?,
    val arguments: String,
    val ok: Boolean,
    val result: String,
    val millis: Long,
)

/** The most recent tool calls, capped so a long day doesn't grow without bound. */
class ToolCallLog(private val capacity: Int = 300) {
    private val calls = ArrayList<ToolCall>()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun record(call: ToolCall) {
        synchronized(calls) {
            calls.add(
                ToolCall(call.time, call.tool, call.sessionId, call.arguments.take(MAX_ARGS), call.ok, call.result.take(MAX_RESULT), call.millis),
            )
            while (calls.size > capacity) calls.removeAt(0)
        }
        listeners.forEach { it() }
    }

    /** Newest first. */
    fun snapshot(): List<ToolCall> = synchronized(calls) { calls.reversed() }

    fun clear() {
        synchronized(calls) { calls.clear() }
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    companion object {
        const val MAX_ARGS = 2_000
        const val MAX_RESULT = 8_000

        /** [calls] (as [snapshot] gives them) as pretty JSON, for "Export". */
        fun toJson(calls: List<ToolCall>): String {
            val array = JsonArray(
                calls.map {
                    buildJsonObject {
                        put("time", Instant.ofEpochMilli(it.time).toString())
                        put("tool", it.tool)
                        put("session", it.sessionId?.let { s -> JsonPrimitive(s) } ?: JsonPrimitive(null as String?))
                        put("arguments", it.arguments)
                        put("status", if (it.ok) "ok" else "error")
                        put("durationMs", it.millis)
                        put("result", it.result)
                    }
                },
            )
            return kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonArray.serializer(), array)
        }
    }
}
