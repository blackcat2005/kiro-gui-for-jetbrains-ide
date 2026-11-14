package com.kirogui.jetbrains.settings

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
    fun `an engine is appended unless blank or already given`() {
        assertEquals("", KiroLaunch.withEngine("", ""))
        assertEquals("--agent x", KiroLaunch.withEngine("--agent x", "  "))
        assertEquals("--agent-engine v2", KiroLaunch.withEngine("", "v2"))
        assertEquals("--agent x --agent-engine v3", KiroLaunch.withEngine("--agent x", "v3"))
        assertEquals("--agent-engine v1", KiroLaunch.withEngine("--agent-engine v1", "v3"), "the user's own argument wins")
        assertEquals(listOf("kiro-cli", "acp", "--agent-engine", "v2"), KiroLaunch.command("kiro-cli", KiroLaunch.withEngine("", "v2")))
    }

    @Test
    fun `blank shell runs directly`() {
        assertEquals(listOf("kiro-cli", "acp"), KiroLaunch.command("kiro-cli", "", "  "))
        assertEquals("", KiroLaunch.compatibleArgs("--agent x --trust-all-tools", "v3"), "v3 refuses every v2-only flag")
        assertEquals("--agent x --trust-all-tools", KiroLaunch.compatibleArgs("--agent x --trust-all-tools", "v2"))
        assertEquals("--agent-engine v3", KiroLaunch.compatibleArgs("--trust-all-tools --agent-engine v3", ""), "engine named in the arguments")
        assertEquals(listOf("kiro-cli", "acp", "--agent", "x"), KiroLaunch.command("other", "kiro-cli acp --agent x"), "a full command is used as typed")
        assertEquals(listOf("C:/k/kiro-cli.exe", "acp", "--agent", "x"), KiroLaunch.command("other", "C:/k/kiro-cli.exe --agent x"), "acp is added when missing")
    }

    @Test
    fun `v3 drops v2-only flags with their values and keeps the rest, in a full command too`() {
        assertEquals(
            "kiro-cli acp --verbose",
            KiroLaunch.compatibleArgs("kiro-cli acp --agent \"my agent\" -a --model=auto --trust-tools fs_read,fs_write --effort high --verbose", "v3"),
        )
        assertEquals("--verbose", KiroLaunch.compatibleArgs("--verbose --agent-engine v3 --agent x".replace(" --agent-engine v3", ""), "v3"))
        assertEquals("--agent x -a", KiroLaunch.compatibleArgs("--agent x -a", ""), "blank engine: kiro-cli itself picks v2 for these flags")
    }

    @Test
    fun `the plugin can tell what the v2-only flags asked for`() {
        val f = KiroLaunch.v2Flags("--agent my-agent --model=claude-haiku-4.5 --trust-all-tools --trust-tools fs_read --effort high --verbose")
        assertEquals("my-agent", f.agent)
        assertEquals("claude-haiku-4.5", f.model)
        assertTrue(f.trustAll)
        assertEquals(listOf("--trust-tools fs_read", "--effort high"), f.dropped)
        assertTrue(KiroLaunch.v2Flags("-a").trustAll)
        assertTrue(!KiroLaunch.v2Flags("--verbose").any)
        assertTrue(KiroLaunch.isV3("", "v3") && KiroLaunch.isV3("--agent-engine v3", "") && KiroLaunch.isV3("--agent-engine=v3", ""))
        assertTrue(!KiroLaunch.isV3("--trust-all-tools", "") && !KiroLaunch.isV3("", "v2"))
    }
}
