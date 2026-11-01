package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Typed Agent Client Protocol client (client side of `kiro-cli acp`).
 * Knows nothing about the IDE: streams, file access and permission UI are injected.
 */
class AcpClient(
    input: InputStream,
    output: OutputStream,
    private val files: FileAccess,
    private val permissions: PermissionHandler,
    private val listener: (AcpEvent) -> Unit,
) : AutoCloseable {

    private val connection = JsonRpcConnection(input, output, object : JsonRpcConnection.Handler {
        override fun onRequest(method: String, params: JsonElement?): JsonElement =
            handleRequest(method, params?.jsonObject ?: JsonObject(emptyMap()))

        override fun onNotification(method: String, params: JsonElement?) {
            when (method) {
                "session/update" -> params?.jsonObject?.let(::handleUpdate)
                "_kiro.dev/metadata" -> params?.jsonObject?.let(::handleMetadata)
                "_kiro.dev/commands/available" -> params?.jsonObject?.let(::handleCommands)
            }
        }

        override fun onClosed(cause: Throwable?) {
            listener(AcpEvent.Closed(cause?.message))
        }
    })

    fun start() = connection.start()

    fun initialize(): JsonElement = connection.request("initialize", buildJsonObject {
        put("protocolVersion", PROTOCOL_VERSION)
        put("clientCapabilities", buildJsonObject {
            put("fs", buildJsonObject {
                put("readTextFile", true)
                put("writeTextFile", true)
            })
        })
        put("clientInfo", buildJsonObject {
            put("name", "kiro-gui-for-jetbrains")
            put("title", "Kiro GUI for JetBrains")
            put("version", "0.1.0")
        })
    }).get(30, TimeUnit.SECONDS)

    fun newSession(cwd: String): SessionInfo {
        val result = connection.request("session/new", buildJsonObject {
            put("cwd", cwd)
            put("mcpServers", JsonArray(emptyList()))
        }).get(30, TimeUnit.SECONDS).jsonObject
        return parseSession(result, result.str("sessionId") ?: error("session/new returned no sessionId"))
    }

    /** Reloads a stored session. Kiro replays the history as `session/update` events before this returns. */
    fun loadSession(sessionId: String, cwd: String): SessionInfo {
        val result = connection.request("session/load", buildJsonObject {
            put("sessionId", sessionId)
            put("cwd", cwd)
            put("mcpServers", JsonArray(emptyList()))
        }).get(60, TimeUnit.SECONDS)
        return parseSession(result as? JsonObject ?: JsonObject(emptyMap()), sessionId)
    }

    private fun parseSession(result: JsonObject, sessionId: String): SessionInfo {
        val modes = result["modes"] as? JsonObject
        val models = result["models"] as? JsonObject
        return SessionInfo(
            sessionId = sessionId,
            modes = modes.choices("availableModes", "id"),
            currentModeId = modes?.str("currentModeId"),
            models = models.choices("availableModels", "modelId"),
            currentModelId = models?.str("currentModelId"),
        )
    }

    fun setMode(sessionId: String, modeId: String) {
        connection.request("session/set_mode", buildJsonObject {
            put("sessionId", sessionId)
            put("modeId", modeId)
        }).get(15, TimeUnit.SECONDS)
    }

    fun setModel(sessionId: String, modelId: String) {
        connection.request("session/set_model", buildJsonObject {
            put("sessionId", sessionId)
            put("modelId", modelId)
        }).get(15, TimeUnit.SECONDS)
    }

    /** Completes with the stop reason (`end_turn`, `cancelled`, ...) when the turn finishes. */
    fun prompt(sessionId: String, text: String): CompletableFuture<String> =
        connection.request("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            put("prompt", buildJsonArray {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        }).thenApply { it.jsonObject["stopReason"]?.jsonPrimitive?.contentOrNull ?: "unknown" }

    fun cancel(sessionId: String) {
        connection.notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    private fun handleRequest(method: String, params: JsonObject): JsonElement = when (method) {
        "session/request_permission" -> requestPermission(params)
        "fs/read_text_file" -> {
            val path = params.str("path") ?: throw JsonRpcException(-32602, "path required")
            val text = sliceLines(
                files.readText(path),
                params["line"]?.jsonPrimitive?.intOrNull,
                params["limit"]?.jsonPrimitive?.intOrNull,
            )
            buildJsonObject { put("content", text) }
        }
        "fs/write_text_file" -> {
            val path = params.str("path") ?: throw JsonRpcException(-32602, "path required")
            files.writeText(path, params.str("content") ?: "")
            JsonNull
        }
        else -> throw JsonRpcException(JsonRpcException.METHOD_NOT_FOUND, "Method not found: $method")
    }

    private fun requestPermission(params: JsonObject): JsonElement {
        val options = params["options"]?.jsonArray?.map {
            val o = it.jsonObject
            PermissionOption(o.str("optionId") ?: "", o.str("name") ?: "", o.str("kind"))
        } ?: emptyList()
        val tool = params["toolCall"] as? JsonObject
        val title = tool?.str("title") ?: "Tool call"
        val chosen = try {
            permissions.request(PermissionRequest(title, options, tool?.get("rawInput") as? JsonObject)).get()
        } catch (_: Exception) {
            null
        }
        return buildJsonObject {
            put("outcome", buildJsonObject {
                if (chosen == null) {
                    put("outcome", "cancelled")
                } else {
                    put("outcome", "selected")
                    put("optionId", chosen)
                }
            })
        }
    }

    private fun handleUpdate(params: JsonObject) {
        val update = params["update"]?.jsonObject ?: return
        val event = when (val type = update.str("sessionUpdate")) {
            "agent_message_chunk" -> AcpEvent.AgentText(update.contentText())
            "agent_thought_chunk" -> AcpEvent.AgentThought(update.contentText())
            "user_message_chunk" -> AcpEvent.UserText(
                update.contentText(),
                ((update["_meta"] as? JsonObject)?.get("kiro") as? JsonObject)?.get("replay")?.jsonPrimitive?.booleanOrNull == true,
            )
            "tool_call" -> AcpEvent.ToolCall(
                update.str("toolCallId") ?: "", update.str("title") ?: "Tool call",
                update.str("kind"), update.str("status"),
            )
            "tool_call_update" -> AcpEvent.ToolCallUpdate(
                update.str("toolCallId") ?: "", update.str("title"), update.str("status"),
            )
            "plan" -> AcpEvent.Plan(update["entries"]?.jsonArray?.mapNotNull { it.jsonObject.str("content") } ?: emptyList())
            else -> AcpEvent.Other(type ?: "unknown")
        }
        listener(event)
    }

    private fun handleCommands(params: JsonObject) {
        val commands = (params["commands"] as? JsonArray)?.map {
            val o = it.jsonObject
            val subs = ((o["meta"] as? JsonObject)?.get("subcommands") as? JsonArray)
                ?.mapNotNull { sub -> sub.jsonPrimitive.contentOrNull } ?: emptyList()
            SlashCommand(o.str("name") ?: "", o.str("description") ?: "", subs)
        } ?: return
        listener(AcpEvent.Commands(commands))
    }

    private fun handleMetadata(params: JsonObject) {
        val credits = (params["meteringUsage"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.doubleOrNull }
            ?.takeIf { it.isNotEmpty() }?.sum()
        listener(AcpEvent.Metadata(params["contextUsagePercentage"]?.jsonPrimitive?.doubleOrNull, credits))
    }

    private fun JsonObject?.choices(listKey: String, idKey: String): List<Choice> =
        (this?.get(listKey) as? JsonArray)?.map {
            val o = it.jsonObject
            val id = o.str(idKey) ?: ""
            Choice(id, o.str("name") ?: id, o.str("description"))
        } ?: emptyList()

    override fun close() = connection.close()

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.contentText(): String =
        this["content"]?.jsonObject?.str("text") ?: ""

    companion object {
        const val PROTOCOL_VERSION = 1
    }
}
