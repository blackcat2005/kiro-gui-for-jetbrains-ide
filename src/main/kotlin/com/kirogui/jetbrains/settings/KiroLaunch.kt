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

    private enum class ShellKind { POWERSHELL, CMD, POSIX }

    /**
     * `kiro-cli acp <extra arguments>`, split like a shell would (quotes keep spaces together). With a [shell]
     * template (e.g. `bash -l -c {cmd}`) the whole line is handed to that shell as one argument, so kiro-cli sees
     * the shell's PATH and profile; a template without `{cmd}` gets the line appended.
     */
    fun command(path: String, extraArgs: String, shell: String = ""): List<String> {
        val parts = listOf(path.trim().ifEmpty { "kiro-cli" }, "acp") + ParametersListUtil.parse(extraArgs.trim())
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
