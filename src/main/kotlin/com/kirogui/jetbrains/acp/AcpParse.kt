package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Reads what `kiro-cli acp` sends, whichever protocol generation answers. The v2 engine reports models under
 * `models` and context/credits through `_kiro.dev/metadata`; the v3 (kas) engine uses `configOptions` and
 * `session_info_update` events. Everything is turned into the same [SessionInfo] and [AcpEvent]s, so the UI never
 * needs to know which engine it is talking to. Unknown shapes are ignored rather than rejected.
 */
internal object AcpParse {

    fun session(result: JsonObject, sessionId: String): SessionInfo {
        val modes = result["modes"] as? JsonObject
        val models = result["models"] as? JsonObject
        val v2Models = models.choices("availableModels", "modelId")
        val modelOption = (result["configOptions"] as? JsonArray)
            ?.mapNotNull { it as? JsonObject }
            ?.firstOrNull { it.str("id") == "model" || it.str("category") == "model" }
        val viaConfig = v2Models.isEmpty() && modelOption != null
        return SessionInfo(
            sessionId = sessionId,
            modes = modes.choices("availableModes", "id"),
            currentModeId = modes?.str("currentModeId"),
            models = if (viaConfig) modelOption.configChoices() else v2Models,
            currentModelId = if (viaConfig) modelOption?.str("currentValue") else models?.str("currentModelId"),
            modelViaConfig = viaConfig,
        )
    }

    /** `session_info_update` carries context use, per-turn credits and errors in `_meta.kiro`. Null when it's none of those. */
    fun sessionInfo(update: JsonObject): AcpEvent? {
        val kiro = ((update["_meta"] as? JsonObject)?.get("kiro") as? JsonObject) ?: return null
        val kind = kiro.str("kind")
        val context = (kiro["contextUsage"] as? JsonObject)?.get("usagePercentage")?.jsonPrimitive?.doubleOrNull
        return when {
            kind == "display_error" -> {
                val message = kiro.str("message") ?: (kiro["displayError"] as? JsonObject)?.str("message")
                message?.takeIf { it.isNotBlank() }?.let { AcpEvent.DisplayError(it) }
            }
            kind == "turn_completion" -> {
                val spent = (kiro["promptTurnSummaries"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonObject)?.get("usage")?.jsonPrimitive?.doubleOrNull }
                    ?.takeIf { it.isNotEmpty() }?.sum()
                spent?.let { AcpEvent.Metadata(null, it) }
            }
            context != null -> AcpEvent.Metadata(context, null)
            else -> null
        }
    }

    /** `available_commands_update`: the v3 engine lists agents you can hand a task to; they are typed as `/name`. */
    fun commands(update: JsonObject): List<SlashCommand> =
        (update["availableCommands"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val name = o.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            SlashCommand(if (name.startsWith("/")) name else "/$name", o.str("description").orEmpty(), emptyList())
        }

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject?.choices(listKey: String, idKey: String): List<Choice> =
        (this?.get(listKey) as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str(idKey) ?: ""
            Choice(id, o.str("name") ?: id, o.str("description"))
        } ?: emptyList()

    private fun JsonObject?.configChoices(): List<Choice> =
        (this?.get("options") as? JsonArray)?.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o.str("value") ?: return@mapNotNull null
            Choice(id, o.str("name") ?: id, o.str("description"))
        } ?: emptyList()
}
