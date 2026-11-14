package com.kirogui.jetbrains.settings

import com.intellij.util.execution.ParametersListUtil

/** Turns the launch settings into a command line; pure text handling so it can be tested without an IDE. */
object KiroLaunch {

    /** A named way to run kiro-cli through a shell; [template] has `{cmd}` where the command line goes. */
    data class ShellPreset(val name: String, val template: String)

    const val DIRECT = "Direct (no shell)"
    const val CUSTOM = "Custom"

    val SHELL_PRESETS = listOf(
        ShellPreset(DIRECT, ""),
        ShellPreset("PowerShell", "powershell -NoLogo -Command {cmd}"),
        ShellPreset("PowerShell 7", "pwsh -NoLogo -Command {cmd}"),
        ShellPreset("Command Prompt", "cmd /c {cmd}"),
        ShellPreset("bash / Git Bash (login)", "bash -l -c {cmd}"),
        ShellPreset("zsh (login)", "zsh -l -c {cmd}"),
        ShellPreset("WSL", "wsl bash -lc {cmd}"),
    )

    const val DEFAULT_SHELL = "Default (PowerShell on Windows, bash elsewhere)"

    /** For Kiro's background commands. `git-bash` stands for wherever Git for Windows put bash.exe. */
    val JOB_SHELL_PRESETS = listOf(
        ShellPreset(DEFAULT_SHELL, ""),
        ShellPreset("Git Bash (found automatically)", "git-bash -c {cmd}"),
        ShellPreset("Git Bash (login shell)", "git-bash -l -c {cmd}"),
        ShellPreset("Command Prompt", "cmd /c {cmd}"),
        ShellPreset("PowerShell 7", "pwsh -NoLogo -NoProfile -Command {cmd}"),
        ShellPreset("WSL", "wsl bash -lc {cmd}"),
        ShellPreset("bash (on PATH)", "bash -c {cmd}"),
    )

    private enum class ShellKind { POWERSHELL, CMD, POSIX }

    /**
     * `kiro-cli acp <extra arguments>`, split like a shell would (quotes keep spaces together). With a [shell]
     * template (e.g. `bash -l -c {cmd}`) the whole line is handed to that shell as one argument, so kiro-cli sees
     * the shell's PATH and profile; a template without `{cmd}` gets the line appended.
     */
    /** [args] with `--agent-engine <engine>` added; unchanged when no engine is chosen or the arguments already name one. */
    fun withEngine(args: String, engine: String): String =
        if (engine.isBlank() || args.contains("--agent-engine")) args else "$args --agent-engine ${engine.trim()}".trim()

    /**
     * Flags only the v2 engine takes (kiro-cli: "not supported with --agent-engine=v3"); the value says whether the flag
     * is followed by a value. [v3Equivalent] describes what the plugin does instead on v3.
     */
    private val V2_ONLY = mapOf(
        "--trust-all-tools" to false, "-a" to false, "--trust-tools" to true,
        "--agent" to true, "--model" to true, "--effort" to true,
    )

    /** The v2-only flags found in some arguments: those the plugin can carry out over ACP, and those it can't. */
    data class V2Flags(
        val agent: String?,
        val model: String?,
        /** `--trust-all-tools`: the v3 engine's `autopilot` option (on by default) is the same thing. */
        val trustAll: Boolean,
        /** Flags with no v3 counterpart (`--trust-tools`, `--effort`), as typed. */
        val dropped: List<String>,
    ) {
        val any: Boolean get() = agent != null || model != null || trustAll || dropped.isNotEmpty()
    }

    /** Is the engine that [args] and the [engine] setting lead to v3? A blank setting leaves it to kiro-cli, which picks v2 for v2-only flags. */
    fun isV3(args: String, engine: String): Boolean {
        val tokens = ParametersListUtil.parse(args.trim())
        return engine.trim() == "v3" || tokens.zipWithNext().any { (a, b) -> a == "--agent-engine" && b == "v3" } || "--agent-engine=v3" in tokens
    }

    /** Walks [args], handing every v2-only flag (and its value) to [onFlag]; the rest is returned. */
    private fun scan(args: String, onFlag: (flag: String, value: String?) -> Unit): List<String> {
        val tokens = ParametersListUtil.parse(args.trim())
        val kept = ArrayList<String>()
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            val name = t.substringBefore('=')
            val takesValue = V2_ONLY[name]
            when {
                takesValue == null -> kept.add(t)
                !takesValue -> onFlag(name, null)
                t.contains('=') -> onFlag(name, t.substringAfter('='))
                else -> {
                    onFlag(name, tokens.getOrNull(i + 1))
                    i++
                }
            }
            i++
        }
        return kept
    }

    /** What [args] ask for through v2-only flags. */
    fun v2Flags(args: String): V2Flags {
        var agent: String? = null
        var model: String? = null
        var trustAll = false
        val dropped = ArrayList<String>()
        scan(args) { flag, value ->
            when (flag) {
                "--agent" -> agent = value
                "--model" -> model = value
                "--trust-all-tools", "-a" -> trustAll = true
                else -> dropped.add(if (value != null) "$flag $value" else flag)
            }
        }
        return V2Flags(agent, model, trustAll, dropped)
    }

    /** [args] without the v2-only flags (and their values) when the engine is v3, which refuses to start with them. */
    fun compatibleArgs(args: String, engine: String): String {
        if (!isV3(args, engine)) return args
        return scan(args) { _, _ -> }.joinToString(" ") { if (it.any(Char::isWhitespace)) "\"$it\"" else it }
    }

    /**
     * Normally `<path> acp <extraArgs>`. When [extraArgs] is itself a full command (first word is a kiro-cli
     * executable, e.g. `kiro-cli acp --agent x`), it is used as typed, with `acp` added if missing.
     */
    fun command(path: String, extraArgs: String, shell: String = ""): List<String> {
        val typed = ParametersListUtil.parse(extraArgs.trim())
        val full = typed.firstOrNull()?.let { !it.startsWith("-") && it.substringAfterLast('/').substringAfterLast('\\').lowercase().removeSuffix(".exe").startsWith("kiro") } == true
        val parts = if (full) typed.let { if ("acp" in it) it else listOf(it.first(), "acp") + it.drop(1) }
        else listOf(path.trim().ifEmpty { "kiro-cli" }, "acp") + typed
        val tokens = ParametersListUtil.parse(shell.trim())
        if (tokens.isEmpty()) return parts
        val line = commandLine(parts, kindOf(tokens.first()))
        return if ("{cmd}" in tokens) tokens.map { if (it == "{cmd}") line else it } else tokens + line
    }

    private fun kindOf(shellExe: String): ShellKind {
        val name = shellExe.substringAfterLast('/').substringAfterLast('\\').lowercase().removeSuffix(".exe")
        return when (name) {
            "powershell", "pwsh" -> ShellKind.POWERSHELL
            "cmd" -> ShellKind.CMD
            else -> ShellKind.POSIX
        }
    }

    private fun commandLine(parts: List<String>, kind: ShellKind): String {
        val quoted = parts.map { quote(it, kind) }
        val line = quoted.joinToString(" ")
        // PowerShell only runs a quoted program path after the call operator.
        return if (kind == ShellKind.POWERSHELL && quoted.first() != parts.first()) "& $line" else line
    }

    private fun quote(s: String, kind: ShellKind): String {
        if (s.isNotEmpty() && s.none { it.isWhitespace() || it in "\"'`$&|;<>()" }) return s
        return when (kind) {
            ShellKind.POWERSHELL -> "'" + s.replace("'", "''") + "'"
            ShellKind.CMD -> "\"" + s + "\""
            ShellKind.POSIX -> "'" + s.replace("'", "'\\''") + "'"
        }
    }

    /** One `KEY=VALUE` per line; blank lines, `#` comments and lines without a key are ignored. */
    fun environment(text: String): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val eq = line.indexOf('=')
            if (eq <= 0) continue
            result[line.substring(0, eq).trim()] = line.substring(eq + 1).trim().removeSurrounding("\"")
        }
        return result
    }
}
