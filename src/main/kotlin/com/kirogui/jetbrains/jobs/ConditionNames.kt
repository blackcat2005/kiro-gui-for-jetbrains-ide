package com.kirogui.jetbrains.jobs

/**
 * The bare names a breakpoint condition (or log expression) uses, so they can be checked against what the file declares
 * before the breakpoint is set. A condition the debugger can't evaluate makes the IDE show a modal "Breakpoint Condition
 * Error" dialog to the user, which Kiro (and a cheaper helper model that guesses names) must not trigger.
 *
 * Deliberately lenient: names starting with a capital (types, constants, enum values), anything after a dot, calls,
 * strings and common keywords are not reported.
 */
object ConditionNames {
    private val strings = Regex("\"(\\\\.|[^\"\\\\])*\"|'(\\\\.|[^'\\\\])*'|`(\\\\.|[^`\\\\])*`")
    private val bare = Regex("(?<![\\w.$@#])([a-z_]\\w*)\\b(?!\\s*\\()")

    private val keywords = setOf(
        "true", "false", "null", "nil", "none", "undefined", "this", "self", "super", "it", "and", "or", "not", "in", "is", "as",
        "new", "instanceof", "typeof", "var", "val", "let", "const", "if", "else", "return", "do", "for", "while", "lambda",
        "int", "long", "short", "byte", "char", "float", "double", "boolean", "void",
    )

    /** The lower-case bare identifiers [expression] reads, in order of first use. */
    fun identifiers(expression: String): List<String> =
        bare.findAll(expression.replace(strings, "\"\"")).map { it.groupValues[1] }
            .filter { it.lowercase() !in keywords }
            .distinct().toList()

    /** The names of [expression] that [declared] does not know; empty when the expression looks fine. */
    fun unknown(expression: String, declared: Set<String>): List<String> = identifiers(expression).filter { it !in declared }
}
