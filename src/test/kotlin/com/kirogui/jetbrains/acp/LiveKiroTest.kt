package com.kirogui.jetbrains.acp

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Talks to a real `kiro-cli acp`. Off unless KIRO_LIVE_CLI points at the executable, e.g.
 * `KIRO_LIVE_CLI=C:\...\kiro-cli.exe ./gradlew test --tests '*LiveKiroTest*' -i`.
 */
class LiveKiroTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "KIRO_LIVE_CLI", matches = ".+")
    fun `handshake and new session against the real kiro-cli`() {
        val exe = System.getenv("KIRO_LIVE_CLI")
        val cwd = System.getenv("KIRO_LIVE_CWD") ?: System.getProperty("user.dir")
        val started = System.nanoTime()
        fun t() = "%6.1fs".format((System.nanoTime() - started) / 1e9)

        val proc = ProcessBuilder(exe, "acp").directory(java.io.File(cwd)).start()
        Thread({ proc.errorStream.bufferedReader().forEachLine { println("${t()} stderr: ${it.take(200)}") } }).apply { isDaemon = true }.start()

        val events = CopyOnWriteArrayList<AcpEvent>()
        val fsCalls = CopyOnWriteArrayList<String>()
        val client = AcpClient(
            input = proc.inputStream,
            output = proc.outputStream,
            files = object : FileAccess {
                override fun readText(path: String): String {
                    fsCalls.add("read $path")
                    println("${t()} fs read $path")
                    return java.io.File(path).readText()
                }

                override fun writeText(path: String, content: String) {
                    fsCalls.add("write $path")
                    println("${t()} fs write $path")
                }
            },
            permissions = PermissionHandler { CompletableFuture.completedFuture(null) },
            listener = {
                events.add(it)
                println("${t()} event ${it.javaClass.simpleName}")
            },
        )
        try {
            client.start()
            println("${t()} initialize...")
            client.initialize()
            println("${t()} initialized")
            val session = client.newSession(cwd)
            println("${t()} session ${session.sessionId} models=${session.models.size} modes=${session.modes.size}")
            assertNotNull(session.sessionId)
            println("${t()} models via config: ${session.modelViaConfig}, ${session.models.map { it.id }}")

            // Switch to a cheap model and send a tiny prompt: context % and credits must arrive as Metadata events.
            val target = session.models.firstOrNull { it.id.contains("haiku") }?.id ?: session.currentModelId
            if (target != null) {
                client.setModel(session.sessionId, target, session.modelViaConfig)
                println("${t()} model set to $target")
            }
            val stop = client.prompt(session.sessionId, "Reply with the single word: ok").get(90, java.util.concurrent.TimeUnit.SECONDS)
            println("${t()} stopReason=$stop")
            val metadata = events.filterIsInstance<AcpEvent.Metadata>()
            println("${t()} metadata: $metadata")
            assertTrue(metadata.any { it.contextPercent != null }, "context usage should be reported")
        } finally {
            println("${t()} fs calls: ${fsCalls.size}, events: ${events.size}")
            client.close()
            proc.descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
        }
    }
}
