package com.kirogui.jetbrains.jobs

/**
 * A best-effort guardrail for `debug_eval`, not a sandbox: evaluating an expression runs code inside the debugged
 * program, so a model could start processes, delete files or change state. [SAFE] refuses the obviously dangerous
 * (process, exit, files, network, reflection, environment); [READ_ONLY] additionally refuses anything that is not a
 * plain read: assignments, ++/--, `new`, statements, and calls to methods that are not known getters.
 */
object EvalGuard {
    const val SAFE = "safe"
    const val READ_ONLY = "readonly"
    const val UNRESTRICTED = "unrestricted"
    val MODES = listOf(SAFE, READ_ONLY, UNRESTRICTED)

    private val dangerous = listOf(
        "Runtime\\s*\\.\\s*getRuntime", "ProcessBuilder", "\\bSystem\\s*\\.\\s*(exit|load|loadLibrary|setProperty|setSecurityManager|getenv)\\b",
        "\\.\\s*(exit|halt)\\s*\\(", "\\bFiles?\\s*\\.", "java\\.io\\.", "java\\.nio\\.", "\\bFile(Writer|OutputStream|InputStream|Reader)?\\s*\\(",
        "java\\.net\\.", "\\bURL(Connection)?\\s*\\(", "\\bSocket\\b", "\\bHttpClient\\b",
        "Class\\s*\\.\\s*forName", "\\.\\s*(getDeclared\\w*|setAccessible|invoke|newInstance|defineClass)\\s*\\(", "\\bMethodHandle", "\\bUnsafe\\b",
        "\\bos\\s*\\.\\s*(system|popen|remove|unlink|rmdir|kill|exec\\w*)\\b", "\\bsubprocess\\b", "\\bshutil\\b", "__import__", "\\bimportlib\\b", "\\bopen\\s*\\(",
        "\\bchild_process\\b", "\\bprocess\\s*\\.\\s*(exit|env|kill|binding)\\b", "\\brequire\\s*\\(", "\\bfs\\s*\\.", "\\bFunction\\s*\\(",
        "\\bexec(ve|le|vp)?\\s*\\(", "\\beval\\s*\\(", "\\bos\\.Exit\\b", "\\bexec\\.Command\\b",
    ).map { Regex(it) }

    private val readOnlyCall = Regex(
        "^(get|is|has|size|length|count|to|equals|hashCode|contains|starts|ends|index|last|first|keys|values|items|" +
            "len|str|repr|type|min|max|abs|sum|sorted|list|dict|set|tuple|int|float|bool|format|substring|trim|lower|upper|" +
            "peek|name|id|ordinal|compare|matches|char|code|at|slice|join|split)",
    )

    private val incrementOrDecrement = Regex("\\+\\+|--")
    private val assignment = Regex("(?<![=!<>+\\-*/%&|^:])=(?![=>])|[+\\-*/%&|^]=|<<=|>>=|:=")
    private val creation = Regex("\\bnew\\s+\\w")
    private val call = Regex("([A-Za-z_][A-Za-z0-9_]*)\\s*\\(")
    private val keywords = setOf("if", "while", "for", "switch", "catch", "return", "and", "or", "not")

    /** Why [expression] is not allowed in [mode], or null if it is. */
    fun check(expression: String, mode: String): String? {
        if (mode == UNRESTRICTED) return null
        val code = stripStrings(expression)
        for (pattern in dangerous) {
            val hit = pattern.find(code) ?: continue
            return "it uses something that can start processes, touch files or the network, use reflection or read the environment (${hit.value.trim()})"
        }
        if (mode != READ_ONLY) return null
        if (incrementOrDecrement.containsMatchIn(code)) return "it changes a variable (++/--)"
        if (assignment.containsMatchIn(code)) return "it assigns a value"
        if (code.contains(';') || code.contains('{')) return "it is a code fragment, not a single expression"
        if (creation.containsMatchIn(code)) return "it creates an object (`new`)"
        for (match in call.findAll(code)) {
            val name = match.groupValues[1]
            if (name in keywords) continue
            if (!readOnlyCall.containsMatchIn(name)) {
                return "it calls `$name(...)`, which may change state (read-only mode allows getters such as getX, isX, size, toString)"
            }
        }
        return null
    }

    fun message(expression: String, mode: String, why: String): String =
        "Not evaluated: $why. The IDE setting \"Evaluate expressions in the debugger\" is \"$mode\" (Settings > Tools > Kiro). " +
            "Prefer reading variables and fields; ask the user to relax the setting if this expression is really needed: `$expression`"

    private fun stripStrings(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '"' || c == '\'') {
                var j = i + 1
                while (j < s.length && s[j] != c) j += if (s[j] == '\\') 2 else 1
                out.append(c).append(c)
                i = j + 1
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
