package com.kirogui.jetbrains.settings

import com.intellij.util.execution.ParametersListUtil

/** Turns the launch settings into a command line; pure text handling so it can be tested without an IDE. */
object KiroLaunch {

    /** `kiro-cli acp <extra arguments>`, split like a shell would (quotes keep spaces together). */
    fun command(path: String, extraArgs: String): List<String> =
        listOf(path.trim().ifEmpty { "kiro-cli" }, "acp") + ParametersListUtil.parse(extraArgs.trim())

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
