package com.kirogui.jetbrains.mcp

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One tool the IDE offers Kiro. [call] runs on a server thread and may block for a few seconds. */
class McpTool(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val call: (McpCall) -> McpResult,
)

/** A tool call: [sessionId] is the ACP session the URL was handed to (null while it is not known yet). */
class McpCall(val sessionId: String?, val arguments: JsonObject)

class McpResult(val text: String, val isError: Boolean = false)

/**
 * A small MCP server (Streamable HTTP, plain JSON responses, no SSE) on the loopback interface. Every ACP session
 * gets its own URL `/mcp/<token>`, so a tool call tells which chat it came from; an unknown token gets 404.
 *
 * kiro-cli 3.1 first sends `server/discover` (MCP 2026-07-28). If that succeeds Kiro skips `initialize` and the tools
 * turn out unusable, so like every method we don't serve it gets "method not found", and Kiro falls back to the
 * classic `initialize` handshake that works.
 */
class McpServer(
    private val serverName: String,
    private val serverVersion: String,
    private val instructions: String?,
    /** Told about every finished tool call (for the history). */
    private val onCall: (ToolCall) -> Unit = {},
    private val tools: () -> List<McpTool>,
) : AutoCloseable {

    private class Binding(@Volatile var sessionId: String?)

    private val bindings = ConcurrentHashMap<String, Binding>()
    private val log = com.intellij.openapi.diagnostic.Logger.getInstance(McpServer::class.java)

    private val pool: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "kiro-gui-mcp").apply { isDaemon = true }
    }
    private val http: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        createContext("/mcp/") { exchange -> handle(exchange) }
        executor = pool
        start()
    }

    val port: Int get() = http.address.port

    /** A fresh secret URL token, optionally already tied to a session. */
    fun newToken(sessionId: String? = null): String {
        val bytes = ByteArray(16).also { RANDOM.nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        bindings[token] = Binding(sessionId)
        return token
    }

    /** Ties [token] to [sessionId] once `session/new` has said which id the chat got. */
    fun bind(token: String, sessionId: String) {
        bindings.getOrPut(token) { Binding(null) }.sessionId = sessionId
    }

    fun url(token: String): String = "http://127.0.0.1:$port/mcp/$token"

    override fun close() {
        http.stop(0)
        pool.shutdownNow()
    }

    private fun handle(exchange: HttpExchange) {
        exchange.use { ex ->
            val token = ex.requestURI.path.removePrefix("/mcp/").trimEnd('/')
            val binding = bindings[token]
            if (binding == null) return respond(ex, 404, null)
            // A web page in the user's browser can reach 127.0.0.1 too; it always sends an Origin. kiro-cli sends none.
            if (!originAllowed(ex.requestHeaders.getFirst("Origin"))) return respond(ex, 403, null)
            when (ex.requestMethod.uppercase()) {
                "POST" -> Unit
                "DELETE" -> return respond(ex, 200, null)
                else -> {
                    ex.responseHeaders.add("Allow", "POST, DELETE")
                    return respond(ex, 405, null)
                }
            }
            val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
            val message = try {
                Json.parseToJsonElement(body) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return respond(ex, 400, error(JsonNull, -32700, "Parse error"))
            val id = message["id"]
            val method = (message["method"] as? JsonPrimitive)?.contentOrNull
            // Notifications (e.g. notifications/initialized) and responses need no answer.
            if (id == null || method == null) return respond(ex, 202, null)
            val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())
            respond(ex, 200, dispatch(id, method, params, binding.sessionId))
        }
    }

    private fun dispatch(id: JsonElement, method: String, params: JsonObject, sessionId: String?): JsonObject = when (method) {
        "initialize" -> result(id, buildJsonObject {
            put("protocolVersion", (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull ?: DEFAULT_PROTOCOL)
            put("capabilities", buildJsonObject { put("tools", buildJsonObject { put("listChanged", false) }) })
            put("serverInfo", buildJsonObject {
                put("name", serverName)
                put("version", serverVersion)
            })
            instructions?.let { put("instructions", it) }
        })
        "ping" -> result(id, JsonObject(emptyMap()))
        "tools/list" -> result(id, buildJsonObject {
            put("tools", buildJsonArray {
                for (tool in tools()) add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("inputSchema", tool.inputSchema)
                })
            })
        })
        "tools/call" -> {
            val name = (params["name"] as? JsonPrimitive)?.contentOrNull
            val tool = tools().firstOrNull { it.name == name }
            if (tool == null) {
                error(id, -32602, "Unknown tool: $name")
            } else {
                val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                val started = System.currentTimeMillis()
                val outcome = try {
                    tool.call(McpCall(sessionId, arguments))
                } catch (e: Exception) {
                    McpResult(e.message ?: e.javaClass.simpleName, isError = true)
                }
                runCatching {
                    onCall(ToolCall(started, tool.name, sessionId, arguments.toString(), !outcome.isError, outcome.text, System.currentTimeMillis() - started))
                }
                // What Kiro was given, for checking the tools by hand: Help → Diagnostic Tools → Debug Log Settings,
                // add `#com.kirogui.jetbrains.mcp`, then look in idea.log.
                if (log.isDebugEnabled) log.debug("$name $arguments -> ${if (outcome.isError) "ERROR " else ""}${outcome.text.take(4000)}")
                result(id, buildJsonObject {
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", outcome.text)
                        })
                    })
                    if (outcome.isError) put("isError", true)
                })
            }
        }
        else -> error(id, -32601, "Method not found: $method")
    }

    private fun respond(ex: HttpExchange, status: Int, body: JsonObject?) {
        if (body == null) {
            ex.sendResponseHeaders(status, -1)
            return
        }
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.sendResponseHeaders(status, bytes.size.toLong())
        ex.responseBody.write(bytes)
    }

    private fun result(id: JsonElement, value: JsonElement) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("result", value)
    }

    private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", id)
        put("error", buildJsonObject {
            put("code", code)
            put("message", message)
        })
    }

    companion object {
        const val DEFAULT_PROTOCOL = "2025-06-18"
        private val RANDOM = SecureRandom()

        /** No Origin (a program such as kiro-cli) or a loopback one is fine; any other site is a browser tab. */
        fun originAllowed(origin: String?): Boolean {
            if (origin.isNullOrBlank()) return true
            val host = try {
                java.net.URI(origin.trim()).host
            } catch (_: Exception) {
                null
            } ?: return false
            return host.equals("localhost", true) || host == "127.0.0.1" || host == "[::1]" || host == "::1"
        }
    }
}

/** Builds the small JSON schemas the tools take. */
object Schema {
    fun obj(required: List<String> = emptyList(), vararg props: Pair<String, JsonObject>): JsonObject = buildJsonObject {
        put("type", "object")
        put("properties", JsonObject(props.toMap()))
        if (required.isNotEmpty()) put("required", JsonArray(required.map { JsonPrimitive(it) }))
    }

    fun string(description: String, enum: List<String>? = null): JsonObject = buildJsonObject {
        put("type", "string")
        put("description", description)
        if (enum != null) put("enum", JsonArray(enum.map { JsonPrimitive(it) }))
    }

    fun integer(description: String): JsonObject = buildJsonObject {
        put("type", "integer")
        put("description", description)
    }

    fun stringArray(description: String): JsonObject = buildJsonObject {
        put("type", "array")
        put("description", description)
        put("items", buildJsonObject { put("type", "string") })
    }

    fun boolean(description: String): JsonObject = buildJsonObject {
        put("type", "boolean")
        put("description", description)
    }
}

/** Reading tool arguments leniently: models sometimes send numbers as strings and booleans as "true". */
fun JsonObject.argString(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

/** An array of strings; a single string counts as a one-element array, and a JSON array sent as text is parsed. */
fun JsonObject.argStrings(key: String): List<String> {
    val v = this[key]
    return when {
        v is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }
        v is JsonPrimitive && v.isString -> {
            val text = v.content.trim()
            if (text.startsWith("[")) {
                runCatching { (Json.parseToJsonElement(text) as JsonArray).mapNotNull { (it as? JsonPrimitive)?.contentOrNull } }
                    .getOrDefault(listOf(text))
            } else if (text.isEmpty()) emptyList() else listOf(text)
        }
        else -> emptyList()
    }
}

fun JsonObject.argInt(key: String): Int? = argString(key)?.trim()?.toDoubleOrNull()?.toInt()

fun JsonObject.argBool(key: String): Boolean? = when (argString(key)?.trim()?.lowercase()) {
    "true", "1", "yes" -> true
    "false", "0", "no" -> false
    else -> null
}
