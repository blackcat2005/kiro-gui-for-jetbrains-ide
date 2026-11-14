package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.kiro.FileLinker
import java.util.Objects

sealed interface ChatBlock {
    /** [time] is when it happened (0 = unknown, e.g. replayed history); shown above the message. */
    class User(val text: String, val attachments: List<Attachment> = emptyList(), val time: Long = System.currentTimeMillis()) : ChatBlock

    class Agent(val text: StringBuilder = StringBuilder(), val time: Long = System.currentTimeMillis()) : ChatBlock {
        // Rendering markdown + highlighting is the costly part and finished answers never change, so keep the result.
        internal var cachedHtml: String? = null
        internal var cacheKey: Int = 0
        internal var cachedWebHtml: String? = null
        internal var webCacheKey: Int = 0
    }

    /** One tool invocation; [status] follows ACP: pending / in_progress / completed / failed. */
    class Tool(val id: String, var title: String, var status: String?) : ChatBlock
    class Note(val text: String) : ChatBlock
}

/** Hex colours without '#', so this stays free of Swing/IDE types and can be rendered in tests. */
data class Palette(
    val fg: String,
    val muted: String,
    val card: String,
    val code: String,
    val accent: String,
    val ok: String,
    val err: String,
    val syntax: SyntaxColors = SyntaxColors.LIGHT,
    /** Optional chat font (CSS family name and point size); null / 0 leave the editor kit's default. */
    val fontFamily: String? = null,
    val fontSize: Int = 0,
    /** The editor's monospace font for code; null leaves the generic one. */
    val codeFont: String? = null,
    /** Row backgrounds in diffs: added, removed, hunk header. */
    val diffAdd: String = "DDF1E0",
    val diffDel: String = "F9DEDE",
    val diffHunk: String = "E4E8F5",
    /** Hairline around cards and code blocks. */
    val border: String = "D5D8DE",
    /** Page background. */
    val bg: String = "FFFFFF",
    /** What Kiro can do with the IDE's tools; shown on the empty chat only. */
    val tools: List<com.kirogui.jetbrains.jobs.ToolCard> = emptyList(),
    /** Is the steering file about those tools installed? null: don't say (the tools are off, or the chat isn't empty). */
    val guidance: Boolean? = null,
)

/**
 * Builds the chat transcript as HTML for Swing's HTML 3.2 renderer, so layout uses tables and `<font>`
 * rather than modern CSS. Colours are embedded on every render, which makes theme changes apply immediately.
 */
object TranscriptHtml {
    private const val STAMP_GAP_MS = 120_000L

    /** The small time label above messages; off by request. */
    const val SHOW_TIME = false

    /** [linker] turns file names in answers into `kirofile:` links; [linkVersion] changes when its answers might. */
    fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker? = null, linkVersion: Int = 0): String {
        val sb = StringBuilder()
        sb.append("<html><head><style>")
        sb.append("body { color: #${p.fg}; margin: 12px;${font(p)} }")
        sb.append("p { margin-top: 0; margin-bottom: 6px; }")
        sb.append("a { color: #${p.accent}; }")
        sb.append("pre { margin: 0; }")
        val mono = p.codeFont?.takeIf { it.isNotBlank() }?.let { "\"${it.replace("\"", "")}\", monospace" } ?: "monospace"
        sb.append("code { font-family: $mono; color: #${p.syntax.function}; }")
        sb.append("tt { font-family: $mono; }")
        sb.append("li { margin-bottom: 2px; }")
        sb.append("h1 { color: #${p.accent}; margin-top: 8px; margin-bottom: 4px; }")
        sb.append("h2 { color: #${p.accent}; margin-top: 8px; margin-bottom: 4px; }")
        sb.append("h3 { color: #${p.syntax.keyword}; margin-top: 6px; margin-bottom: 3px; }")
        sb.append("h4 { color: #${p.syntax.keyword}; margin-top: 6px; margin-bottom: 3px; }")
        sb.append("blockquote { color: #${p.muted}; margin-left: 10px; }")
        sb.append("th { color: #${p.syntax.keyword}; }")
        sb.append("table { border-color: #${p.muted}; }")
        sb.append("</style></head><body>")
        if (blocks.isEmpty()) sb.append(welcome(p))
        var lastStamp = 0L
        blocks.forEachIndexed { i, block ->
            // The time is shown when it has moved on, not on every message of one exchange.
            val time = when (block) { is ChatBlock.User -> block.time; is ChatBlock.Agent -> block.time; else -> 0L }
            val showTime = time > 0 && (lastStamp == 0L || time - lastStamp >= STAMP_GAP_MS)
            if (showTime) lastStamp = time
            when (block) {
                is ChatBlock.User -> sb.append(user(block, p, showTime))
                is ChatBlock.Agent -> sb.append(agent(i, block, p, linker, linkVersion, showTime))
                is ChatBlock.Tool -> sb.append(tool(block, p))
                is ChatBlock.Note -> sb.append(note(block, p))
            }
            // Consecutive tool rows sit together; everything else gets breathing room.
            val next = blocks.getOrNull(i + 1)
            // The rail already pads its own rows, so between two rail rows a spacer would cut the line.
            if (!(onRail(block) && next != null && onRail(next))) sb.append(spacer(if (block is ChatBlock.Agent || block is ChatBlock.User) 8 else 6))
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    private fun font(p: Palette): String = buildString {
        p.fontFamily?.takeIf { it.isNotBlank() }?.let { append(" font-family: \"").append(it.replace("\"", "")).append("\";") }
        if (p.fontSize > 0) append(" font-size: ${p.fontSize}pt;")
    }

    private fun welcome(p: Palette): String {
        return spacer(16) + toolCards(p) +
            "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\"><font size=\"-1\" color=\"#${p.muted}\">" +
            "Enter sends &middot; Shift+Enter adds a line &middot; <b>/</b> commands &middot; <b>@</b> files and skills<br>" +
            "Ctrl+Alt+K attaches the selected code. Long pastes become chips. File names open with Ctrl+click.<br>" +
            "You review every diff before Kiro changes a file.</font></td></tr></table>"
    }

    /** "What Kiro can do in your IDE": one card per kind of tool; a click puts a sample prompt into the input box. */
    private fun toolCards(p: Palette): String {
        if (p.tools.isEmpty()) return ""
        // Swing's HTML has no rounded corners, flex or grid: a table of two columns. The cells of a row share a height and
        // cellspacing is the gap between them, which nested tables can't give.
        fun cell(i: Int, card: com.kirogui.jetbrains.jobs.ToolCard) =
            "<td width=\"50%\" valign=\"top\" bgcolor=\"#${p.card}\">" +
                "<a href=\"tool:$i\"><font color=\"#${p.accent}\"><b>${MarkdownHtml.escape(card.title)}</b></font></a><br>" +
                "<font color=\"#${p.fg}\">${MarkdownHtml.escape(card.blurb)}</font><br>" +
                "<font size=\"-2\" face=\"monospaced\" color=\"#${p.muted}\">${card.tools.joinToString(" &middot; ") { MarkdownHtml.escape(it) }}</font></td>"
        val cards = "<table width=\"100%\" cellpadding=\"10\" cellspacing=\"6\">" + p.tools.withIndex().chunked(2).joinToString("") { row ->
            "<tr>" + row.joinToString("") { (i, card) -> cell(i, card) } + (if (row.size == 1) "<td width=\"50%\"></td>" else "") + "</tr>"
        } + "</table>"
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"center\">" +
            "<font color=\"#${p.muted}\"><b>WHAT KIRO CAN DO IN YOUR IDE</b></font><br>" +
            "<font size=\"-1\" color=\"#${p.muted}\">Click one to try it</font></td></tr></table>" + spacer(8) +
            guidanceBanner(p) + cards + spacer(10)
    }

    /** The call to action: Kiro only uses the tools well once its steering file says so; one click installs it. */
    private fun guidanceBanner(p: Palette): String {
        val installed = p.guidance ?: return ""
        val body = if (installed) {
            "<font color=\"#${p.ok}\"><b>&#10003; Kiro has been told to use these tools.</b></font><br>" +
                "<font size=\"-1\" color=\"#${p.muted}\">The steering file is installed and kept up to date, so it still applies after Kiro compacts a chat.</font>"
        } else {
            "<a href=\"guidance:install\"><font color=\"#${p.accent}\"><b>Install Kiro guidance for all Kiro GUI tools</b></font></a><br>" +
                "<font color=\"#${p.fg}\">Kiro doesn't know about these tools yet. This adds a short, always-on steering file so it uses them " +
                "(and keeps using them after a compaction).</font>"
        }
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.accent}\" width=\"3\"></td><td>" +
            "<table width=\"100%\" cellpadding=\"1\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.border}\"><table width=\"100%\" cellpadding=\"10\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.card}\">$body</td></tr></table></td></tr></table></td></tr></table>" + spacer(10)
    }

    /** Your message: a quiet card with a hairline border, no label (like Claude for VS Code). */
    private fun user(b: ChatBlock.User, p: Palette, showTime: Boolean): String {
        val text = if (b.text.isBlank()) "" else MarkdownHtml.escape(b.text)
        val cards = b.attachments.joinToString("") { spacer(6) + attachmentCard(it, p) }
        return stamp(if (showTime) b.time else 0, p) +
            "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td width=\"4\" bgcolor=\"#${p.accent}\"></td>" +
            "<td><table width=\"100%\" cellpadding=\"10\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.card}\">$text$cards</td></tr></table></td></tr></table>"
    }

    /** The time, small and muted at the right, above a message. */
    private fun stamp(time: Long, p: Palette): String =
        if (!SHOW_TIME || time <= 0) "" else "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr><td align=\"right\">" +
            "<font size=\"-2\" color=\"#${p.muted}\">${java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).format(java.util.Date(time))}</font>" +
            "</td></tr></table>" + spacer(3)

    /** Answers, tool calls and job news sit on the timeline; your messages and plain notes do not. */
    private fun onRail(b: ChatBlock): Boolean = when (b) {
        is ChatBlock.Agent, is ChatBlock.Tool -> true
        is ChatBlock.Note -> b.text.startsWith("⚙")
        is ChatBlock.User -> false
    }

    /**
     * A row on the timeline: a dot in the left gutter, a one-pixel line in [rail] colour running the full height of
     * the row (so the dots are joined), and the content beside it.
     */
    private fun timeline(dotColor: String, inner: String, rail: String): String {
        // Swing HTML can't overlay a dot on a line, so both are drawn into one background image of the gutter (one
        // bitmap, so a scaled display moves them together; see RailImages). The inner table keeps the cell at least as
        // tall as the dot.
        val image = RailImages.rail(dotColor, rail)
        return "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>" +
            "<td width=\"20\" valign=\"top\" style=\"background-image: url($image); background-repeat: repeat-y\">" +
            "<table width=\"20\" cellpadding=\"0\" cellspacing=\"0\"><tr><td width=\"20\" height=\"14\"></td></tr></table></td>" +
            "<td valign=\"top\">$inner${spacer(4)}</td></tr></table>"
    }

    private fun dot(color: String) = color

    /** A one-pixel outline in [Palette.border] around a [bg] filled cell: Swing's HTML has no border-radius or border. */
    private fun framed(bg: String, p: Palette, pad: Int, inner: String): String =
        "<table width=\"100%\" cellpadding=\"1\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.border}\">" +
            "<table width=\"100%\" cellpadding=\"$pad\" cellspacing=\"0\"><tr><td bgcolor=\"#$bg\">$inner</td></tr></table>" +
            "</td></tr></table>"

    /** A compact card under the message: a file name, or the first lines of a snippet with a count of the rest. */
    private fun attachmentCard(a: Attachment, p: Palette): String {
        val label = "<font size=\"-2\" color=\"#${p.muted}\">"
        val body = when (a) {
            is Attachment.File ->
                "${label}FILE</font>&nbsp;&nbsp;<a href=\"${MarkdownHtml.fileLink(a.path, null)}\"><font color=\"#${p.accent}\">${MarkdownHtml.escape(a.path)}</font></a>"
            is Attachment.Image -> {
                val url = ImageAttachments.thumbnailUrl(a)
                val (w, h) = ImageAttachments.thumbnailSize(a)
                label + "IMAGE</font>&nbsp;&nbsp;<b>${MarkdownHtml.escape(a.label)}</b>" +
                    (if (url != null) "<br><img src=\"$url\" width=\"$w\" height=\"$h\">" else "")
            }
            is Attachment.Snippet -> {
                val (head, more) = Attachments.preview(a.code)
                label + (if (a.source == null) "PASTED" else "CODE") + "</font>&nbsp;&nbsp;<b>${MarkdownHtml.escape(a.label)}</b><br>" +
                    "<font size=\"-1\"><tt>${codeHtml(head, a.language.ifEmpty { null }, p)}</tt></font>" +
                    (if (more > 0) "<br><font size=\"-2\" color=\"#${p.muted}\"><i>&hellip; $more more ${if (more == 1) "line" else "lines"}</i></font>" else "")
            }
        }
        return "<table width=\"100%\" cellpadding=\"5\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.code}\">$body</td></tr></table>"
    }

    /** Highlighted code with line breaks and indentation made explicit, so Swing can wrap it at spaces. */
    private fun codeHtml(raw: String, lang: String?, p: Palette): String =
        CodeHighlighter.highlight(raw, lang, p.syntax)
            .replace("\t", "    ")
            .replace("  ", "&nbsp; ")
            .replace("\n", "<br>")
            .replace("<br> ", "<br>&nbsp;")

    private fun agent(index: Int, b: ChatBlock.Agent, p: Palette, linker: FileLinker?, linkVersion: Int, showTime: Boolean): String {
        val key = Objects.hash(b.text.length, p, linkVersion, index, linker != null, showTime)
        b.cachedHtml?.let { if (b.cacheKey == key) return it }

        var md = MarkdownHtml.render(b.text.toString(), linker)
        md = md.replace("<table>", "<table border=\"1\" bordercolor=\"#${p.muted}\" cellpadding=\"5\" cellspacing=\"0\">")
        val body = codeBlocks(md, p, index)
        val html = stamp(if (showTime) b.time else 0, p) + timeline(
            dot(p.accent),
            body + "<font size=\"-2\"><a href=\"copy:$index\"><font color=\"#${p.muted}\">Copy</font></a></font>",
            p.border,
        )
        b.cachedHtml = html
        b.cacheKey = key
        return html
    }

    private fun tool(b: ChatBlock.Tool, p: Palette): String {
        val (mark, color) = when (b.status) {
            "completed" -> "&#10003;" to p.ok
            "failed" -> "&#10007;" to p.err
            else -> "&hellip;" to p.muted
        }
        // "@kirogui/bg_run" is the server's name for the tool; the person reading doesn't need it.
        val title = b.title.replace(Regex("@[A-Za-z0-9_-]+/"), "")
        return timeline(dot(color), "<font color=\"#${p.muted}\">${MarkdownHtml.escape(title)}</font>&nbsp;<font color=\"#$color\">$mark</font>", p.border)
    }

    private val exitCode = Regex("exit code (-?\\d+)")

    private fun note(b: ChatBlock.Note, p: Palette): String {
        if (!b.text.startsWith("\u2699")) return "<font color=\"#${p.muted}\"><i>${MarkdownHtml.escape(b.text).replace("\n", "<br>")}</i></font>"
        // News of a background job or the debugger: a row like a tool call, green when it went well, red when not.
        val code = exitCode.find(b.text)?.groupValues?.get(1)?.toIntOrNull()
        val color = when {
            code != null -> if (code == 0) p.ok else p.err
            b.text.contains("failed", true) -> p.err
            else -> p.muted
        }
        return timeline(dot(color), "<font color=\"#${p.muted}\">${MarkdownHtml.escape(b.text.removePrefix("\u2699").trim())}</font>", p.border)
    }

    private val codeBlock = Regex("<pre><code(?: class=\"language-([^\"]*)\")?[^>]*>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)

    /**
     * Swing neither shades `<pre>` nor wraps it, so long lines would be clipped. Code goes into a shaded table
     * cell instead, syntax-coloured, with line breaks and indentation made explicit so it can wrap at spaces.
     * Each block gets a language label and a Copy link (`copycode:<block>:<n>`).
     */
    private fun codeBlocks(html: String, p: Palette, blockIndex: Int): String {
        var n = 0
        return codeBlock.replace(html) { m ->
            val lang = m.groupValues[1].ifEmpty { null }
            val raw = unescape(m.groupValues[2]).trimEnd('\n')
            val index = n++
            if (CodeHighlighter.isDiff(lang)) return@replace diffBlock(raw, p, blockIndex, index)
            val header = "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>" +
                "<td><font size=\"-2\" color=\"#${p.muted}\">${(lang ?: "code").lowercase()}</font></td>" +
                "<td align=\"right\"><a href=\"copycode:$blockIndex:$index\"><font size=\"-2\" color=\"#${p.muted}\">Copy</font></a></td>" +
                "</tr></table>" + spacer(4)
            framed(p.bg, p, 8, header + "<font color=\"#${p.fg}\"><tt>${codeHtml(raw, lang, p)}</tt></font>") + spacer(8)
        }
    }

    /**
     * A diff as one row per line: a narrow +/- column and the text, on a tinted background (green added, red
     * removed), with the file name and counts on top. Copy takes the diff as written, or just the new side.
     */
    private fun diffBlock(raw: String, p: Palette, blockIndex: Int, index: Int): String {
        val lines = DiffText.parse(raw)
        val (added, removed) = DiffText.stats(lines)
        val file = DiffText.path(lines)
        val small = "<font size=\"-2\" color=\"#${p.muted}\">"
        val header = small + "DIFF</font>&nbsp;&nbsp;" +
            (file?.let { "<b>${MarkdownHtml.escape(it)}</b>&nbsp;&nbsp;" } ?: "") +
            "<font size=\"-2\" color=\"#${p.syntax.added}\"><b>+$added</b></font>&nbsp;" +
            "<font size=\"-2\" color=\"#${p.syntax.removed}\"><b>&minus;$removed</b></font>&nbsp;&nbsp;" +
            "<a href=\"copycode:$blockIndex:$index\"><font size=\"-2\">Copy diff</font></a>&nbsp;&nbsp;" +
            "<a href=\"copycode:$blockIndex:$index:new\"><font size=\"-2\">Copy new</font></a><br>"
        val rows = lines.joinToString("") { l ->
            val (bg, fg, mark) = when (l.kind) {
                DiffText.Kind.ADD -> Triple(p.diffAdd, p.syntax.added, "+")
                DiffText.Kind.DEL -> Triple(p.diffDel, p.syntax.removed, "&minus;")
                DiffText.Kind.HUNK -> Triple(p.diffHunk, p.syntax.keyword, "")
                DiffText.Kind.HEADER -> Triple(p.bg, p.muted, "")
                DiffText.Kind.CONTEXT -> Triple(p.bg, p.fg, "")
            }
            val text = codeHtml(l.text, null, p).ifEmpty { "&nbsp;" }
            "<tr><td width=\"14\" valign=\"top\" bgcolor=\"#$bg\"><font color=\"#$fg\"><tt><b>$mark</b></tt></font></td>" +
                "<td bgcolor=\"#$bg\"><font color=\"#${if (l.kind == DiffText.Kind.CONTEXT) p.fg else fg}\"><tt>$text</tt></font></td></tr>"
        }
        return framed(p.bg, p, 8, header + "<table width=\"100%\" cellpadding=\"2\" cellspacing=\"0\">$rows</table>") + spacer(8)
    }

    /** commonmark escapes `& < > "` inside code; the highlighter wants the raw text. */
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun spacer(height: Int) =
        "<table cellpadding=\"0\" cellspacing=\"0\"><tr><td height=\"$height\"></td></tr></table>"
}
