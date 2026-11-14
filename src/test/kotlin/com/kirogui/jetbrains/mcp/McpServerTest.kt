package com.kirogui.jetbrains.mcp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class McpServerTest {

    private val calls = mutableListOf<Pair<String?, JsonObject>>()
    private val server = McpServer("kirogui", "test", "Use the tools.") {
        listOf(
            McpTool("echo", "Echoes `text`.", Schema.obj(listOf("text"), "text" to Schema.string("Text."))) { call ->
                calls.add(call.sessionId to call.arguments)
                McpResult("echo:" + call.arguments.argString("text"))
            },
            McpTool("boom", "Fails.", Schema.obj()) { throw IllegalStateException("it broke") },
        )
    }
    private val http = HttpClient.newHttpClient()

    @AfterEach
    fun stop() = server.close()

    private fun post(token: String, body: String): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create(server.url(token)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    private fun rpc(token: String, id: Int, method: String, params: String = "{}"): JsonObject {
        val response = post(token, """{"jsonrpc":"2.0","id":$id,"method":"$method","params":$params}""")
        assertEquals(200, response.statusCode())
        assertTrue(response.headers().firstValue("Content-Type").orElse("").startsWith("application/json"))
        return Json.parseToJsonElement(response.body()).jsonObject
    }

    @Test
    fun `classic handshake, tool list and a call that knows its session`() {
        val token = server.newToken()
        server.bind(token, "session-1")

        val init = rpc(token, 1, "initialize", """{"protocolVersion":"2025-11-25","capabilities":{},"clientInfo":{"name":"kiro"}}""")
        val result = init["result"]!!.jsonObject
        assertEquals("2025-11-25", result["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("kirogui", result["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("Use the tools.", result["instructions"]!!.jsonPrimitive.content)

        assertEquals(202, post(token, """{"jsonrpc":"2.0","method":"notifications/initialized"}""").statusCode())

        val tools = rpc(token, 2, "tools/list")["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(listOf("echo", "boom"), tools.map { it.jsonObject["name"]!!.jsonPrimitive.content })
        assertEquals("object", tools[0].jsonObject["inputSchema"]!!.jsonObject["type"]!!.jsonPrimitive.content)

        val call = rpc(token, 3, "tools/call", """{"name":"echo","arguments":{"text":"hi"}}""")["result"]!!.jsonObject
        assertEquals("echo:hi", call["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertNull(call["isError"])
        assertEquals("session-1", calls.single().first)
    }

    @Test
    fun `server discover is refused so kiro falls back to initialize`() {
        val token = server.newToken("s")
        val reply = rpc(token, 1, "server/discover")
        assertEquals(-32601, reply["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `a failing tool is an error result, an unknown tool a protocol error`() {
        val token = server.newToken("s")
        val failed = rpc(token, 1, "tools/call", """{"name":"boom","arguments":{}}""")["result"]!!.jsonObject
        assertEquals("true", failed["isError"]!!.jsonPrimitive.content)
        assertTrue(failed["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content.contains("it broke"))

        val unknown = rpc(token, 2, "tools/call", """{"name":"nope","arguments":{}}""")
        assertEquals(-32602, unknown["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `unknown tokens are not served and each token is its own chat`() {
        assertEquals(404, post("not-a-token", """{"jsonrpc":"2.0","id":1,"method":"ping"}""").statusCode())
        val a = server.newToken("a")
        val b = server.newToken("b")
        assertNotEquals(a, b)
        assertEquals(32, a.length)
        rpc(a, 1, "tools/call", """{"name":"echo","arguments":{"text":"1"}}""")
        rpc(b, 1, "tools/call", """{"name":"echo","arguments":{"text":"2"}}""")
        assertEquals(listOf("a", "b"), calls.map { it.first })
    }

    @Test
    fun `get is not allowed, bad json is a parse error`() {
        val token = server.newToken("s")
        val get = http.send(HttpRequest.newBuilder(URI.create(server.url(token))).GET().build(), HttpResponse.BodyHandlers.ofString())
        assertEquals(405, get.statusCode())
        val bad = post(token, "{nope")
        assertEquals(400, bad.statusCode())
        assertEquals(-32700, Json.parseToJsonElement(bad.body()).jsonObject["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `lenient argument reading`() {
        val args = Json.parseToJsonElement("""{"n":"12","m":3.0,"b":"true","blank":"  "}""").jsonObject
        assertEquals(12, args.argInt("n"))
        assertEquals(3, args.argInt("m"))
        assertEquals(true, args.argBool("b"))
        assertNull(args.argString("blank"))
        assertNull(args.argInt("missing"))
    }
}
