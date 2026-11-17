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

/** A picture sent with a prompt (ACP `image` content block). */
class PromptImage(val mimeType: String, val data: ByteArray)

/** The `prompt` array: the text (when there is any, or when there is nothing else), then one block per image. */
fun promptContent(text: String, images: List<PromptImage>): JsonArray = buildJsonArray {
    if (text.isNotBlank() || images.isEmpty()) add(buildJsonObject {
        put("type", "text")
        put("text", text)
    })
    for (image in images) add(buildJsonObject {
        put("type", "image")
        put("mimeType", image.mimeType)
        put("data", java.util.Base64.getEncoder().encodeToString(image.data))
    })
}

/** An MCP server reached over HTTP, as `session/new` and `session/load` take it. */
fun httpMcpServer(name: String, url: String): JsonObject = buildJsonObject {
    put("type", "http")
    put("name", name)
    put("url", url)
    put("headers", JsonArray(emptyList()))
}

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
    /**
     * Like [listener] but also told which session an event belongs to (null: none named). When set it is used
     * instead, so several chats can share the process.
     */
    private val sessionListener: ((String?, AcpEvent) -> Unit)? = null,
) : AutoCloseable {

    private val connection = JsonRpcConnection(input, output, object : JsonRpcConnection.Handler {
        override fun onRequest(method: String, params: JsonElement?): JsonElement =
            handleRequest(method, params?.jsonObject ?: JsonObject(emptyMap()))

        override fun onNotification(method: String, params: JsonElement?) {
            when (method) {
                "session/update" -> params?.jsonObject?.let(::handleUpdate)
                "_kiro.dev/metadata" -> params?.jsonObject?.let(::handleMetadata)
                "_kiro.dev/commands/available" -> params?.jsonObject?.let(::handleCommands)
                else -> logUnhandled("notification", method, params)
            }
        }

        override fun onClosed(cause: Throwable?) {
            val closed = AcpEvent.Closed(cause?.message)
            sessionListener?.invoke(null, closed) ?: listener(closed)
        }
    })

    /**
     * Updates and notifications for any other session are dropped. One process can host several sessions, and
     * the one you switched away from may still be finishing; its events must not land in the new transcript.
     * Null means "accept everything".
     */
    @Volatile
    var activeSession: String? = null

    /** How long the handshake and opening or reloading a session may take. Set from the settings page. */
    @Volatile
    var startTimeoutSeconds: Long = 120

    private fun emit(params: JsonObject, event: AcpEvent) {
        val sessionListener = sessionListener
        if (sessionListener != null) sessionListener(params["sessionId"]?.jsonPrimitive?.contentOrNull, event) else listener(event)
    }

    private fun foreign(params: JsonObject): Boolean {
        val active = activeSession ?: return false
        val sid = params["sessionId"]?.jsonPrimitive?.contentOrNull ?: return false
        return sid != active
    }

    /** The agent said in `initialize` that it can connect to MCP servers over HTTP. */
    @Volatile
    var supportsHttpMcp: Boolean = false
        private set

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
            put("version", "0.1.3")
        })
    }).get(startTimeoutSeconds, TimeUnit.SECONDS).also { supportsHttpMcp = AcpParse.httpMcp(it) }

    /** [mcpServers] are extra MCP servers for this session only (the IDE's own tools). */
    fun newSession(cwd: String, mcpServers: JsonArray = JsonArray(emptyList())): SessionInfo {
        val result = connection.request("session/new", buildJsonObject {
            put("cwd", cwd)
            put("mcpServers", mcpServers)
        }).get(startTimeoutSeconds, TimeUnit.SECONDS).jsonObject
        return parseSession(result, result.str("sessionId") ?: error("session/new returned no sessionId"))
    }

    /** Reloads a stored session. Kiro replays the history as `session/update` events before this returns. */
    fun loadSession(sessionId: String, cwd: String, mcpServers: JsonArray = JsonArray(emptyList())): SessionInfo {
        val result = connection.request("session/load", buildJsonObject {
            put("sessionId", sessionId)
            put("cwd", cwd)
            put("mcpServers", mcpServers)
        }).get(startTimeoutSeconds, TimeUnit.SECONDS)
        return parseSession(result as? JsonObject ?: JsonObject(emptyMap()), sessionId)
    }

    private fun parseSession(result: JsonObject, sessionId: String): SessionInfo = AcpParse.session(result, sessionId)

    fun setMode(sessionId: String, modeId: String) {
        connection.request("session/set_mode", buildJsonObject {
            put("sessionId", sessionId)
            put("modeId", modeId)
        }).get(15, TimeUnit.SECONDS)
    }

    /**
     * The v2 engine has `session/set_model`; the v3 engine changes it through the `model` config option instead.
     * [viaConfigOptions] says which to try first, and the other is the fallback if that one is refused.
     */
    fun setModel(sessionId: String, modelId: String, viaConfigOptions: Boolean = false) {
        val legacy = {
            connection.request("session/set_model", buildJsonObject {
                put("sessionId", sessionId)
                put("modelId", modelId)
            }).get(15, TimeUnit.SECONDS)
        }
        val configOption = {
            connection.request("session/set_config_option", buildJsonObject {
                put("sessionId", sessionId)
                put("configId", "model")
                put("value", modelId)
            }).get(15, TimeUnit.SECONDS)
        }
        val (first, second) = if (viaConfigOptions) configOption to legacy else legacy to configOption
        try {
            first()
        } catch (e: Exception) {
            try {
                second()
            } catch (_: Exception) {
                throw e
            }
        }
    }

    fun setConfigOption(sessionId: String, configId: String, value: String) {
        connection.request("session/set_config_option", buildJsonObject {
            put("sessionId", sessionId)
            put("configId", configId)
            put("value", value)
        }).get(15, TimeUnit.SECONDS)
    }

    /** Completes with the stop reason (`end_turn`, `cancelled`, ...) when the turn finishes. */
    fun prompt(sessionId: String, text: String, images: List<PromptImage> = emptyList()): CompletableFuture<String> =
        connection.request("session/prompt", buildJsonObject {
            put("sessionId", sessionId)
            put("prompt", promptContent(text, images))
        }).thenApply { it.jsonObject["stopReason"]?.jsonPrimitive?.contentOrNull ?: "unknown" }

    /** Summarises the conversation to free context (kiro-cli 3.x `_kiro/session/compact`; typing `/compact` does not do it). */
    fun compact(sessionId: String) {
        connection.request("_kiro/session/compact", buildJsonObject { put("sessionId", sessionId) }).get(120, TimeUnit.SECONDS)
    }

    /** Ends a session so its MCP connections go away (kiro-cli 3.x lists `close` among its session capabilities). */
    fun closeSession(sessionId: String) {
        connection.request("session/close", buildJsonObject { put("sessionId", sessionId) }).get(10, TimeUnit.SECONDS)
    }

    fun cancel(sessionId: String) {
        connection.notify("session/cancel", buildJsonObject { put("sessionId", sessionId) })
    }

    /** Plan credits for the account (Kiro's `/usage`). Null when the command fails or has no credit figures. */
    fun usage(sessionId: String): PlanUsage? {
        // The v3 engine has its own method (same `data` shape); v2 only knows the slash command below.
        val direct = try {
            connection.request("_kiro/account/getUsage", buildJsonObject { put("sessionId", sessionId) }).get(20, TimeUnit.SECONDS) as? JsonObject
        } catch (e: Exception) {
            null
        }
        direct?.takeIf { it["success"]?.jsonPrimitive?.booleanOrNull != false }?.let { d ->
            (d["data"] as? JsonObject)?.let { data -> PlanUsage.parse(data)?.let { return it } }
        }
        val result = connection.request("_kiro.dev/commands/execute", buildJsonObject {
            put("sessionId", sessionId)
            put("command", buildJsonObject {
                put("command", "usage")
                put("args", buildJsonObject {})
            })
        }).get(20, TimeUnit.SECONDS) as? JsonObject ?: return null
        if (result["success"]?.jsonPrimitive?.booleanOrNull == false) return null
        return PlanUsage.parse(result["data"] as? JsonObject ?: return null)
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
        else -> {
            logUnhandled("request", method, params)
            throw JsonRpcException(JsonRpcException.METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    /**
     * Diagnostics for messages this client does not understand (for example an MCP server asking the user to sign in).
     * Everything goes to idea.log at INFO; anything carrying a URL is also logged as a WARN so it is easy to find.
     */
    private fun logUnhandled(kind: String, what: String, payload: JsonElement?) {
        val text = payload?.toString().orEmpty()
        val shown = if (text.length > 2000) text.take(2000) + "…(${text.length} chars)" else text
        log.info("Unhandled ACP $kind: $what $shown")
        if (URL_IN_TEXT.containsMatchIn(text)) log.warn("ACP $kind '$what' carries a URL (maybe a login link): $shown")
    }

    private fun requestPermission(params: JsonObject): JsonElement {
        val options = params["options"]?.jsonArray?.map {
            val o = it.jsonObject
            PermissionOption(o.str("optionId") ?: "", o.str("name") ?: "", o.str("kind"))
        } ?: emptyList()
        val tool = params["toolCall"] as? JsonObject
        val title = tool?.str("title") ?: "Tool call"
        val chosen = try {
            permissions.request(PermissionRequest(title, options, tool?.get("rawInput") as? JsonObject, params.str("sessionId"))).get()
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
        if (foreign(params)) return
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
                update.str("kind"), update.str("status"), update.locations(),
            )
            "tool_call_update" -> AcpEvent.ToolCallUpdate(
                update.str("toolCallId") ?: "", update.str("title"), update.str("status"), update.locations(),
            )
            "plan" -> AcpEvent.Plan(update["entries"]?.jsonArray?.mapNotNull { it.jsonObject.str("content") } ?: emptyList())
            "session_info_update" -> AcpParse.sessionInfo(update) ?: AcpEvent.Other(type)
            "available_commands_update" -> AcpEvent.Commands(AcpParse.commands(update))
            "config_option_update" -> AcpParse.configUpdate(update) ?: AcpEvent.Other(type)
            else -> {
                logUnhandled("session update", type ?: "unknown", update)
                AcpEvent.Other(type ?: "unknown")
            }
        }
        emit(params, event)
    }

    private fun handleCommands(params: JsonObject) {
        if (foreign(params)) return
        val commands = (params["commands"] as? JsonArray)?.map {
            val o = it.jsonObject
            val subs = ((o["meta"] as? JsonObject)?.get("subcommands") as? JsonArray)
                ?.mapNotNull { sub -> sub.jsonPrimitive.contentOrNull } ?: emptyList()
            SlashCommand(o.str("name") ?: "", o.str("description") ?: "", subs)
        } ?: return
        emit(params, AcpEvent.Commands(commands))
    }

    private fun handleMetadata(params: JsonObject) {
        if (foreign(params)) return
        val credits = (params["meteringUsage"] as? JsonArray)
            ?.mapNotNull { it.jsonObject["value"]?.jsonPrimitive?.doubleOrNull }
            ?.takeIf { it.isNotEmpty() }?.sum()
        emit(params, AcpEvent.Metadata(params["contextUsagePercentage"]?.jsonPrimitive?.doubleOrNull, credits))
    }

    private fun JsonObject?.choices(listKey: String, idKey: String): List<Choice> =
        (this?.get(listKey) as? JsonArray)?.map {
            val o = it.jsonObject
            val id = o.str(idKey) ?: ""
            Choice(id, o.str("name") ?: id, o.str("description"))
        } ?: emptyList()

    override fun close() = connection.close()

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull

    private fun JsonObject.locations(): List<String> =
        (this["locations"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.str("path") } ?: emptyList()

    private fun JsonObject.contentText(): String =
        this["content"]?.jsonObject?.str("text") ?: ""

    companion object {
        const val PROTOCOL_VERSION = 1
        private val log = com.intellij.openapi.diagnostic.Logger.getInstance(AcpClient::class.java)
        private val URL_IN_TEXT = Regex("https?://[^\\s\"\\\\]+")
    }
}
