package com.kirogui.jetbrains.jobs

import com.kirogui.jetbrains.acp.AcpClient
import com.kirogui.jetbrains.acp.AcpEvent
import com.kirogui.jetbrains.acp.FileAccess
import com.kirogui.jetbrains.acp.PermissionHandler
import com.kirogui.jetbrains.acp.httpMcpServer
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpServer
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.argString
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole event-driven loop against a real `kiro-cli acp`, without the IDE: the plugin's MCP server and wake
 * queue, a stand-in `bg_run` that runs the command with [ShellCommand], and the wake prompt after the job ends.
 * Off unless KIRO_LIVE_CLI points at the executable:
 * `KIRO_LIVE_CLI=kiro-cli ./gradlew test --tests '*LiveBackgroundJobTest*' -i`.
 */
class LiveBackgroundJobTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "KIRO_LIVE_CLI", matches = ".+")
    fun `kiro starts a job through the IDE server, ends its turn, and is woken with the result`() {
        val exe = System.getenv("KIRO_LIVE_CLI")
        val cwd = System.getenv("KIRO_LIVE_CWD") ?: System.getProperty("java.io.tmpdir")
        val started = System.nanoTime()
        fun log(s: String) = println("%6.1fs %s".format((System.nanoTime() - started) / 1e9, s))

        val wake = WakeQueue()
        val jobEnded = CompletableFuture<Int>()
        var calledFrom: String? = null
        val windows = System.getProperty("os.name").startsWith("Windows")
        val server = McpServer("kirogui", "live-test", JobService.INSTRUCTIONS) {
            listOf(
                McpTool("bg_run", "Start a shell command in the background and return at once with a job id. You " +
                    "automatically get a message with the exit code and output when it ends, so do not poll, sleep or " +
                    "wait: end your turn.", Schema.obj(listOf("command"), "command" to Schema.string("The command."))) { call ->
                    val command = call.arguments.argString("command")!!
                    calledFrom = call.sessionId
                    log("bg_run from ${call.sessionId}: $command")
                    val job = Job(1, JobKind.COMMAND, "test", command, call.sessionId)
                    Thread {
                        val p = ProcessBuilder(ShellCommand.build(command, windows)).redirectErrorStream(true).start()
                        job.output.append(p.inputStream.readBytes().toString(Charsets.UTF_8))
                        val code = p.waitFor()
                        job.end(if (code == 0) JobState.FINISHED else JobState.FAILED, code)
                        log("job ended with $code")
                        wake.post(Notice(call.sessionId!!, "job-1", JobText.endedSummary(job), JobText.ended(job)))
                        jobEnded.complete(code)
                    }.start()
                    McpResult("Started background job #1. You will get a message when it ends; do not poll or wait.")
                },
            )
        }

        val proc = ProcessBuilder(exe, "acp").directory(java.io.File(cwd)).start()
        Thread { proc.errorStream.bufferedReader().forEachLine { } }.apply { isDaemon = true }.start()
        val text = StringBuilder()
        val client = AcpClient(
            input = proc.inputStream,
            output = proc.outputStream,
            files = object : FileAccess {
                override fun readText(path: String) = java.io.File(path).readText()
                override fun writeText(path: String, content: String) {}
            },
            permissions = PermissionHandler { request ->
                log("permission: ${request.toolTitle}")
                CompletableFuture.completedFuture(request.options.firstOrNull { it.kind == "allow_once" }?.id ?: request.options.first().id)
            },
            listener = { if (it is AcpEvent.AgentText) synchronized(text) { text.append(it.text) } },
        )
        try {
            client.start()
            client.initialize()
            assertTrue(client.supportsHttpMcp, "kiro-cli should accept HTTP MCP servers")
            val token = server.newToken()
            val session = client.newSession(cwd, JsonArray(listOf(httpMcpServer("kirogui", server.url(token)))))
            server.bind(token, session.sessionId)
            client.activeSession = session.sessionId
            if (session.models.any { it.id == "claude-haiku-4.5" }) client.setModel(session.sessionId, "claude-haiku-4.5", session.modelViaConfig)
            Thread.sleep(2000) // let Kiro connect to the server

            val command = if (windows) "Start-Sleep -Seconds 3; Write-Output 'tests: 41 passed, 1 failed (test_login)'; cmd /c exit 3"
            else "sleep 3; echo 'tests: 41 passed, 1 failed (test_login)'; exit 3"
            val first = client.prompt(
                session.sessionId,
                "Run this test command in the background with the kirogui bg_run tool (not your own shell tool), then " +
                    "end your turn without waiting: $command",
            ).get(180, TimeUnit.SECONDS)
            log("first turn: $first; Kiro said: ${text.toString().take(300)}")
            assertEquals(session.sessionId, calledFrom, "the tool call must arrive on this chat's URL")
            assertEquals(3, jobEnded.get(60, TimeUnit.SECONDS))

            val notices = wake.take(session.sessionId)
            assertEquals(1, notices.size)
            synchronized(text) { text.setLength(0) }
            val second = client.prompt(session.sessionId, WakePrompt.compose(notices)).get(180, TimeUnit.SECONDS)
            val reply = synchronized(text) { text.toString() }
            log("wake turn: $second; Kiro said: ${reply.take(600)}")
            assertEquals("end_turn", second)
            assertTrue(reply.contains("3") || reply.contains("test_login") || reply.contains("fail", ignoreCase = true), reply)
        } finally {
            client.close()
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
            server.close()
        }
    }
}
