package com.kirogui.jetbrains.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class KiroLaunchTest {

    @Test
    fun `command is path, acp, then the extra arguments`() {
        assertEquals(listOf("kiro-cli", "acp"), KiroLaunch.command("", ""))
        assertEquals(
            listOf("C:/Tools/kiro-cli.exe", "acp", "--agent", "my agent", "--trust-all-tools"),
            KiroLaunch.command(" C:/Tools/kiro-cli.exe ", "--agent \"my agent\" --trust-all-tools"),
        )
    }

    @Test
    fun `environment reads KEY=VALUE lines`() {
        val env = KiroLaunch.environment(
            """
            # proxy
            HTTPS_PROXY=http://localhost:8080
            KIRO_LOG="debug"

            =novalue
            nonsense
            EMPTY=
            """.trimIndent(),
        )
        assertEquals(mapOf("HTTPS_PROXY" to "http://localhost:8080", "KIRO_LOG" to "debug", "EMPTY" to ""), env)
    }
}
