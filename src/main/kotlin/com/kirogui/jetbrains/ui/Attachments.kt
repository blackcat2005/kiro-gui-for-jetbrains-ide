package com.kirogui.jetbrains.ui

/** Something that rides along with a prompt but is kept apart from the text you typed. */
sealed interface Attachment {
    /** What the chip and the transcript card say. */
    val label: String

    /** A file the agent should read itself; [path] is relative to the project when it lives in it. */
    data class File(val path: String) : Attachment {
        override val label: String get() = path.substringAfterLast('/').substringAfterLast('\\')
    }

    /** A pasted or chosen picture, already scaled and encoded ([mime] is image/png or image/jpeg). */
    class Image(val mime: String, val bytes: ByteArray, val width: Int, val height: Int) : Attachment {
        internal var thumb: java.io.File? = null
        override val label: String get() = "Image · ${width}×$height"
    }

    /** Code from the editor ([source] such as `src/Foo.kt:10-25`) or a long paste ([source] null). */
    data class Snippet(val code: String, val language: String = "", val source: String? = null) : Attachment {
        val lineCount: Int get() = code.trimEnd('\n').lines().size
        override val label: String get() = (source ?: "Pasted text") + " · " + (if (lineCount == 1) "1 line" else "$lineCount lines")
    }
}

object Attachments {

    /** A paste this long becomes a chip instead of filling the input box. */
    fun isLongPaste(text: String): Boolean = text.length >= 500 || text.count { it == '\n' } >= 6

    /**
     * The text actually sent to Kiro: the prompt, then the attachments as plain, unambiguous sections
     * (files are named so the agent reads them; snippets are fenced with a fence the code cannot close).
     */
    fun compose(prompt: String, items: List<Attachment>): String {
        val sb = StringBuilder(prompt.trim())
        fun gap() {
            if (sb.isNotEmpty()) sb.append("\n\n")
        }
        val files = items.filterIsInstance<Attachment.File>()
        if (files.isNotEmpty()) {
            gap()
            sb.append(if (files.size == 1) "Attached file: " else "Attached files: ")
            sb.append(files.joinToString(", ") { "`${it.path}`" })
        }
        for (s in items.filterIsInstance<Attachment.Snippet>()) {
            gap()
            val fence = fence(s.code)
            sb.append(s.source?.let { "From `$it`:" } ?: "Pasted text:").append('\n')
            sb.append(fence).append(s.language).append('\n').append(s.code.trimEnd('\n')).append('\n').append(fence)
        }
        return sb.toString()
    }

    /** At least three backticks, and longer than any run of backticks inside [code]. */
    fun fence(code: String): String {
        var n = 3
        while (code.contains("`".repeat(n))) n++
        return "`".repeat(n)
    }

    /** The first [max] lines of a snippet and how many were left out. */
    fun preview(code: String, max: Int = 5): Pair<String, Int> {
        val lines = code.trimEnd('\n').lines()
        return lines.take(max).joinToString("\n") to (lines.size - max).coerceAtLeast(0)
    }
}
