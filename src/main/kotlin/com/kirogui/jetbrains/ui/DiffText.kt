package com.kirogui.jetbrains.ui

/** Splits a unified diff into typed lines, so it can be drawn as rows and copied in more than one shape. */
object DiffText {

    enum class Kind { HEADER, HUNK, ADD, DEL, CONTEXT }

    /** [text] has the +/-/space marker removed for ADD, DEL and CONTEXT; headers and hunks keep theirs. */
    data class Line(val kind: Kind, val text: String) {
        val marker: String get() = when (kind) { Kind.ADD -> "+"; Kind.DEL -> "-"; else -> " " }
    }

    private val HEADER_START = listOf("diff ", "index ", "--- ", "+++ ", "new file", "deleted file", "similarity ", "rename ", "old mode", "new mode", "Binary files")

    fun parse(code: String): List<Line> {
        val out = ArrayList<Line>()
        var header = true // before the first hunk of a file, "--- a/x" and "+++ b/x" are file names, not changes
        for (raw in code.trimEnd('\n').split("\n")) {
            val line = raw.trimEnd('\r')
            when {
                line.startsWith("diff ") -> { header = true; out.add(Line(Kind.HEADER, line)) }
                header && HEADER_START.any { line.startsWith(it) } -> out.add(Line(Kind.HEADER, line))
                line.startsWith("@@") -> { header = false; out.add(Line(Kind.HUNK, line)) }
                line.startsWith("\\") -> out.add(Line(Kind.HUNK, line)) // "\ No newline at end of file"
                line.startsWith("+") -> { header = false; out.add(Line(Kind.ADD, line.drop(1))) }
                line.startsWith("-") -> { header = false; out.add(Line(Kind.DEL, line.drop(1))) }
                line.startsWith(" ") -> { header = false; out.add(Line(Kind.CONTEXT, line.drop(1))) }
                else -> out.add(Line(Kind.CONTEXT, line))
            }
        }
        return out
    }

    /** The file after the change: context and added lines, no markers. Ready to paste into an editor. */
    fun newSide(code: String): String = side(code, Kind.ADD)

    /** The file before the change. */
    fun oldSide(code: String): String = side(code, Kind.DEL)

    private fun side(code: String, keep: Kind): String =
        parse(code).filter { it.kind == keep || it.kind == Kind.CONTEXT }.joinToString("\n") { it.text }

    /** (added, removed) line counts. */
    fun stats(lines: List<Line>): Pair<Int, Int> = lines.count { it.kind == Kind.ADD } to lines.count { it.kind == Kind.DEL }

    /** The changed file's name from the `+++ b/path` (or `--- a/path`) header, if there is one. */
    fun path(lines: List<Line>): String? {
        val headers = lines.filter { it.kind == Kind.HEADER }.map { it.text }
        val name = (headers.firstOrNull { it.startsWith("+++ ") && !it.endsWith("/dev/null") }
            ?: headers.firstOrNull { it.startsWith("--- ") && !it.endsWith("/dev/null") })
            ?.drop(4)?.substringBefore('\t')?.trim() ?: return null
        return name.removePrefix("a/").removePrefix("b/").ifEmpty { null }
    }
}
