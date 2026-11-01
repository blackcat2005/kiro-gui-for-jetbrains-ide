package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

class AcpClientTest {

    /** A scripted fake agent on the other end of two pipes. */
    private class FakeAgent {
        val toClient = PipedOutputStream()
        val fromClient = PipedInputStream(1 shl 16)
        private val clientOut = PipedOutputStream(fromClient)
        val clientIn = PipedInputStream(toClient, 1 shl 16)
        val received = CopyOnWriteArrayList<JsonObject>()

        fun send(line: String) {
            toClient.write((line + "\n").toByteArray())
            toClient.flush()
        }

        fun clientOutput() = clientOut

        fun readLoop(onMessage: (JsonObject) -> Unit) = Thread {
            fromClient.bufferedReader().forEachLine {
                val obj = Json.parseToJsonElement(it).jsonObject
                received.add(obj)
                onMessage(obj)
            }
        }.apply { isDaemon = true; start() }
    }

    @Test
    fun `handshake, streaming prompt and permission round trip`() {
        val agent = FakeAgent()
        val events = CopyOnWriteArrayList<AcpEvent>()
        val permissionAsked = CompletableFuture<PermissionRequest>()
        val client = AcpClient(
            input = agent.clientIn,
            output = agent.clientOutput(),
            files = object : FileAccess {
                override fun readText(path: String) = "a\nb\nc"
                override fun writeText(path: String, content: String) {}
            },
            permissions = PermissionHandler { req ->
                permissionAsked.complete(req)
                CompletableFuture.completedFuture("allow_once")
            },
            listener = { events.add(it) },
        )

        agent.readLoop { msg ->
            val id = msg["id"]?.jsonPrimitive?.content
            when (msg["method"]?.jsonPrimitive?.content) {
                "initialize" -> agent.send("""{"jsonrpc":"2.0","id":$id,"result":{"protocolVersion":1}}""")
                "session/new" -> agent.send("""{"jsonrpc":"2.0","id":$id,"result":{"sessionId":"s1","modes":{"currentModeId":"kiro_default","availableModes":[{"id":"kiro_default","name":"kiro_default","description":"d"},{"id":"lean","name":"lean"}]},"models":{"currentModelId":"auto","availableModels":[{"modelId":"auto","name":"auto"},{"modelId":"claude-haiku-4.5","name":"claude-haiku-4.5"}]}}}""")
                "session/prompt" -> {
                    agent.send("""{"jsonrpc":"2.0","method":"_kiro.dev/commands/available","params":{"sessionId":"s1","commands":[{"name":"/context","description":"Manage context","meta":{"subcommands":["show","add"]}},{"name":"/clear","description":"Clear"}]}}""")
                    agent.send("""{"jsonrpc":"2.0","method":"_kiro.dev/metadata","params":{"sessionId":"s1","contextUsagePercentage":4.5,"meteringUsage":[{"value":0.25,"unit":"credit"}]}}""")
                    agent.send("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Hel"}}}}""")
                    agent.send("""{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"s1","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"lo"}}}}""")
                    agent.send("""{"jsonrpc":"2.0","id":900,"method":"session/request_permission","params":{"sessionId":"s1","toolCall":{"toolCallId":"t1","title":"Run ls"},"options":[{"optionId":"allow_once","name":"Allow","kind":"allow_once"}]}}""".replace("\"title\":\"Run ls\"}", "\"title\":\"Run ls\",\"rawInput\":{\"command\":\"ls\"}}"))
                }
                null -> if (msg["id"]?.jsonPrimitive?.content == "900") {
                    // permission answered -> finish the turn (prompt request id is 3)
                    agent.send("""{"jsonrpc":"2.0","id":3,"result":{"stopReason":"end_turn"}}""")
                }
            }
        }

        client.start()
        client.initialize()
        val info = client.newSession("/tmp")
        assertEquals("s1", info.sessionId)
        assertEquals(listOf("kiro_default", "lean"), info.modes.map { it.id })
        assertEquals("kiro_default", info.currentModeId)
        assertEquals(listOf("auto", "claude-haiku-4.5"), info.models.map { it.id })
        assertEquals("auto", info.currentModelId)
        val stop = client.prompt(info.sessionId, "hi").get(5, TimeUnit.SECONDS)

        assertEquals("end_turn", stop)
        assertEquals("Hello", events.filterIsInstance<AcpEvent.AgentText>().joinToString("") { it.text })
        assertEquals(AcpEvent.Metadata(4.5, 0.25), events.filterIsInstance<AcpEvent.Metadata>().single())
        val asked = permissionAsked.get(1, TimeUnit.SECONDS)
        assertEquals("Run ls", asked.toolTitle)
        assertEquals("ls", asked.rawInput!!["command"]!!.jsonPrimitive.content)
        val cmds = events.filterIsInstance<AcpEvent.Commands>().last().commands
        assertEquals(listOf("/context", "/clear"), cmds.map { it.name })
        assertEquals(listOf("show", "add"), cmds[0].subcommands)
        val reply = agent.received.first { it["id"]?.jsonPrimitive?.content == "900" }
        assertEquals("selected", reply["result"]!!.jsonObject["outcome"]!!.jsonObject["outcome"]!!.jsonPrimitive.content)
        assertEquals("allow_once", reply["result"]!!.jsonObject["outcome"]!!.jsonObject["optionId"]!!.jsonPrimitive.content)
        client.close()
    }

    @Test
    fun `sliceLines is 1-based`() {
        assertEquals("b\nc", sliceLines("a\nb\nc\nd", 2, 2))
        assertEquals("c\nd", sliceLines("a\nb\nc\nd", 3, null))
        assertEquals("a\nb\nc\nd", sliceLines("a\nb\nc\nd", null, null))
    }
}
