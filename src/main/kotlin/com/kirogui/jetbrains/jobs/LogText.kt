package com.kirogui.jetbrains.jobs

import java.util.concurrent.ConcurrentHashMap

/**
 * A log's kept lines, numbered from the start of the run (1-based) even after the oldest were dropped, so a line
 * keeps its number from one read to the next.
 */
class NumberedLines(
    /** The number of `lines[0]`. */
    val first: Long,
    val lines: List<String>,
    /** False while the last line is still being written (no newline yet). */
    val lastComplete: Boolean,
) {
    /** The number of the last line (`first - 1` if there are none). */
    val total: Long get() = first - 1 + lines.size

    /** The last line that won't change any more. */
    val complete: Long get() = if (lastComplete || lines.isEmpty()) total else total - 1

    companion object {
        /** [text] split into lines, `\r\n` as one break, a line rewritten with `\r` (progress bars) as its final text. */
        fun of(text: String, first: Long = 1): NumberedLines {
            if (text.isEmpty()) return NumberedLines(first, emptyList(), true)
            val parts = text.replace("\r\n", "\n").split('\n').map(::collapse)
            val complete = text.endsWith("\n")
            return NumberedLines(first, if (complete) parts.dropLast(1) else parts, complete)
        }

        fun collapse(line: String): String = line.trimEnd('\r').substringAfterLast('\r')
    }
}

/** Lines [first]..[last] of a log of [total] lines. */
class LogWindow(
    val text: String,
    val first: Long,
    val last: Long,
    val total: Long,
    /** Lines asked for that are gone (only the last 256K characters are kept). */
    val lost: Long,
    /** Lines after [last] not shown. */
    val after: Long,
    /** Where a reader who saw this window has read up to: the last shown line that won't change any more. */
    val readTo: Long,
) {
    val shown: Int get() = (last - first + 1).toInt().coerceAtLeast(0)
}

/** Picks lines out of a log for Kiro. Pure, so it is testable. */
object LogText {

    /**
     * Lines from [from] on (all kept lines if null), at most [lines] lines and about [maxChars] characters: the newest
     * of them if [newest], else the first ones (reading forward).
     */
    fun window(log: NumberedLines, from: Long?, lines: Int, maxChars: Int, newest: Boolean): LogWindow {
        val start = maxOf(from ?: 1L, log.first)
        val lost = if (from != null && from < log.first) log.first - from else 0L
        val startIdx = (start - log.first).coerceIn(0, log.lines.size.toLong()).toInt()
        val range = log.lines.subList(startIdx, log.lines.size)
        val counted = if (newest) range.takeLast(lines.coerceAtLeast(1)) else range.take(lines.coerceAtLeast(1))
        val picked = fit(counted, maxChars, fromEnd = newest)
        val first = if (newest) start + (range.size - picked.size) else start
        val last = first + picked.size - 1
        return LogWindow(
            text = picked.joinToString("\n"),
            first = first,
            last = last,
            total = log.total,
            lost = lost,
            after = (log.total - last).coerceAtLeast(0),
            readTo = minOf(maxOf(last, start - 1), log.complete),
        )
    }

    /** The last [lines] lines from [from] on that contain [contains] (any case), each prefixed with its number. */
    fun grep(log: NumberedLines, contains: String, from: Long?, lines: Int, maxChars: Int = 20_000): Pair<String, Int> {
        val needle = contains.lowercase()
        val start = maxOf(from ?: 1L, log.first)
        val hits = log.lines.withIndex()
            .map { (log.first + it.index) to it.value }
            .filter { (n, line) -> n >= start && line.lowercase().contains(needle) }
        val shown = fit(hits.takeLast(lines.coerceAtLeast(1)).map { (n, line) -> "$n: $line" }, maxChars, fromEnd = true)
        return shown.joinToString("\n") to hits.size
    }

    /** Whole lines up to about [maxChars]; a single longer line is cut. */
    private fun fit(lines: List<String>, maxChars: Int, fromEnd: Boolean): List<String> {
        val limit = maxChars.coerceAtLeast(10)
        val kept = ArrayList<String>()
        var size = 0
        for (line in if (fromEnd) lines.asReversed() else lines) {
            if (size + line.length + 1 > limit) {
                if (kept.isEmpty()) kept.add(if (fromEnd) "…" + line.takeLast(limit - 1) else line.take(limit - 1) + "…")
                break
            }
            kept.add(line)
            size += line.length + 1
        }
        return if (fromEnd) kept.asReversed() else kept
    }
}

/**
 * Reads logs for Kiro and remembers, per chat and log, how far it has read, so `new_only` gives just what was
 * printed since and Kiro need not read the same lines twice.
 */
class LogReader {
    private val cursors = ConcurrentHashMap<String, Long>()

    /**
     * What was printed since [chat] last read [key]: the newest [max] lines of it, and the number of the first new
     * line (so the caller can say how much was left out). Null if nothing is new. Counts all of it as read.
     */
    fun newest(chat: String?, key: String, log: NumberedLines, max: Int, maxChars: Int = 3000): Pair<LogWindow, Long>? {
        val cursorKey = "${chat.orEmpty()}/$key"
        val seen = cursors[cursorKey] ?: 0L
        if (log.total <= seen) return null
        val w = LogText.window(log, seen + 1, max, maxChars, newest = true)
        cursors[cursorKey] = maxOf(seen, w.readTo)
        return if (w.shown == 0) null else w to seen + 1
    }

    fun read(
        chat: String?,
        /** Names the log, e.g. `run-3` or `job-8`. */
        key: String,
        title: String,
        log: NumberedLines,
        lines: Int,
        fromLine: Long? = null,
        newOnly: Boolean = false,
        contains: String? = null,
        maxChars: Int = 20_000,
    ): String {
        val cursorKey = "${chat.orEmpty()}/$key"
        val seen = cursors[cursorKey]
        val from: Long? = when {
            fromLine != null -> fromLine.coerceAtLeast(1)
            newOnly && seen != null -> seen + 1
            else -> null
        }
        if (log.lines.isEmpty() && log.total == 0L) return "$title: no output yet."
        if (from != null && from > log.total) {
            return if (newOnly && fromLine == null) "$title: nothing new since line $seen (that is all of it)."
            else "$title has ${log.total} lines; there is no line $from."
        }
        val range = if (from != null) "from line $from" else null

        if (contains != null) {
            val (hits, count) = LogText.grep(log, contains, from, lines, maxChars)
            cursors[cursorKey] = log.complete
            if (count == 0) return "$title: no line ${range?.let { "$it " } ?: ""}contains \"$contains\" (${log.total} lines)."
            return "$title, $count line(s) ${range?.let { "$it " } ?: ""}containing \"$contains\"" +
                (if (count > lines) ", last $lines shown" else "") + " (of ${log.total} lines):\n$hits"
        }

        val w = LogText.window(log, from, lines, maxChars, newest = from == null)
        cursors[cursorKey] = w.readTo
        return buildString {
            append(title).append(": lines ").append(w.first).append('-').append(w.last).append(" of ").append(w.total)
            if (w.lost > 0) append(" (the ${w.lost} lines before ${log.first} are no longer kept)")
            append(":\n").append(w.text)
            if (w.after > 0) append("\n[${w.after} more lines; next: from_line=${w.last + 1}, or new_only]")
        }
    }
}
