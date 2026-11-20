package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** One thing Kiro (v3 engine) remembers: a preference of yours or a fact about a workspace / repository. */
data class Memory(
    val id: String,
    /** `USER_PREFERENCE`, `SEMANTIC` or `UNKNOWN`. */
    val type: String,
    /** Where it applies: `workspace:<name>`, `repo:<owner>/<repo>`, ... */
    val scopes: List<String>,
    val title: String,
    val summary: String,
    val content: String,
    val why: String?,
    val howToApply: String?,
    /** ISO timestamp of the last change, as Kiro wrote it. */
    val updatedAt: String?,
) {
    val isPreference: Boolean get() = type == "USER_PREFERENCE"
}

/** How much of Kiro's memory a session may use (the `memory.mode` setting of Kiro, per session here). */
enum class MemoryMode(val wire: String, val label: String) {
    DISABLED("disabled", "Off"),
    READ_ONLY("read_only", "Read only: Kiro knows what it remembered"),
    READ_WRITE("read_write", "Read and write: Kiro can also remember new things");

    companion object {
        fun of(wire: String?): MemoryMode? = entries.firstOrNull { it.wire == wire }
    }
}

object MemoryParse {
    /** The records of a `_kiro/memory/list` answer. */
    fun list(result: JsonElement?): List<Memory> =
        ((result as? JsonObject)?.get("memories") as? JsonArray)?.mapNotNull { one(it) }.orEmpty()

    /** The cursor for the next page of a `_kiro/memory/list` answer, if there is one. */
    fun nextCursor(result: JsonElement?): String? = (result as? JsonObject)?.str("nextCursor")?.takeIf { it.isNotBlank() }

    /** A record, or the `memory` of a `_kiro/memory/get` answer. */
    fun one(element: JsonElement?): Memory? {
        val o = element as? JsonObject ?: return null
        val record = (o["memory"] as? JsonObject) ?: o
        val id = record.str("id") ?: return null
        return Memory(
            id = id,
            type = record.str("memoryType") ?: "UNKNOWN",
            scopes = (record["scopes"] as? JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }.orEmpty(),
            title = record.str("title").orEmpty(),
            summary = record.str("summary").orEmpty(),
            content = record.str("content").orEmpty(),
            why = record.str("why"),
            howToApply = record.str("howToApply"),
            updatedAt = record.str("updatedAt") ?: record.str("createdAt"),
        )
    }

    /**
     * `session/new` / `session/load` `_meta` that sets the session's memory: `_meta.kiro.settings.memory`. Null when the
     * user left it to Kiro's own settings (no mode chosen).
     */
    fun sessionMeta(mode: MemoryMode?, reflection: Boolean): JsonObject? {
        mode ?: return null
        return buildJsonObject {
            put("kiro", buildJsonObject {
                put("settings", buildJsonObject {
                    put("memory", buildJsonObject {
                        put("mode", mode.wire)
                        put("reflection", reflection && mode == MemoryMode.READ_WRITE)
                    })
                })
            })
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
}
