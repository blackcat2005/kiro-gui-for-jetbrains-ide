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

    @Test
    fun `a shell template receives the command line as one argument`() {
        assertEquals(
            listOf("bash", "-l", "-c", "kiro-cli acp --agent 'my agent'"),
            KiroLaunch.command("kiro-cli", "--agent \"my agent\"", "bash -l -c {cmd}"),
        )
        assertEquals(listOf("wsl", "bash", "-lc", "kiro-cli acp"), KiroLaunch.command("", "", "wsl bash -lc {cmd}"))
        // no {cmd}: the line is appended
        assertEquals(listOf("zsh", "-lc", "kiro-cli acp"), KiroLaunch.command("kiro-cli", "", "zsh -lc"))
    }

    @Test
    fun `powershell needs the call operator for a quoted path`() {
        assertEquals(
            listOf("powershell", "-Command", "& 'C:\\Program Files\\Kiro\\kiro-cli.exe' acp"),
            KiroLaunch.command("C:\\Program Files\\Kiro\\kiro-cli.exe", "", "powershell -Command {cmd}"),
        )
        assertEquals(
            listOf("C:\\Windows\\System32\\cmd.exe", "/c", "\"C:\\a b\\k.exe\" acp"),
            KiroLaunch.command("C:\\a b\\k.exe", "", "C:\\Windows\\System32\\cmd.exe /c {cmd}"),
        )
    }

    @Test
    fun `blank shell runs directly`() {
        assertEquals(listOf("kiro-cli", "acp"), KiroLaunch.command("kiro-cli", "", "  "))
    }
}
