package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.kiro.FileLinker
import java.util.Objects

sealed interface ChatBlock {
    class User(val text: String, val attachments: List<Attachment> = emptyList()) : ChatBlock

    class Agent(val text: StringBuilder = StringBuilder()) : ChatBlock {
        // Rendering markdown + highlighting is the costly part and finished answers never change, so keep the result.
        internal var cachedHtml: String? = null
        internal var cacheKey: Int = 0
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
    /** Row backgrounds in diffs: added, removed, hunk header. */
    val diffAdd: String = "DDF1E0",
    val diffDel: String = "F9DEDE",
    val diffHunk: String = "E4E8F5",
)

/**
 * Builds the chat transcript as HTML for Swing's HTML 3.2 renderer, so layout uses tables and `<font>`
 * rather than modern CSS. Colours are embedded on every render, which makes theme changes apply immediately.
 */
object TranscriptHtml {

    /** [linker] turns file names in answers into `kirofile:` links; [linkVersion] changes when its answers might. */
    fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker? = null, linkVersion: Int = 0): String {
        val sb = StringBuilder()
        sb.append("<html><head><style>")
        sb.append("body { color: #${p.fg}; margin: 8px;${font(p)} }")
        sb.append("p { margin-top: 0; margin-bottom: 6px; }")
        sb.append("a { color: #${p.accent}; }")
        sb.append("pre { margin: 0; }")
        sb.append("code { font-family: monospace; color: #${p.syntax.function}; }")
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
        blocks.forEachIndexed { i, block ->
            when (block) {
                is ChatBlock.User -> sb.append(user(block, p))
                is ChatBlock.Agent -> sb.append(agent(i, block, p, linker, linkVersion))
                is ChatBlock.Tool -> sb.append(tool(block, p))
                is ChatBlock.Note -> sb.append(note(block, p))
            }
            // Consecutive tool rows sit together; everything else gets breathing room.
            val next = blocks.getOrNull(i + 1)
            if (!(block is ChatBlock.Tool && next is ChatBlock.Tool)) sb.append(spacer(10))
        }
        sb.append("</body></html>")
        return sb.toString()
    }

    /** Quick starters on the empty chat: the label is shown, a click puts the prompt into the input box. */
    val SUGGESTIONS = listOf(
        "Explain this project" to "Give me a quick tour of this project: what it does, how it is laid out and where to start reading.",
        "Find bugs in my changes" to "Review my uncommitted changes and point out bugs, risky spots and anything missing.",
        "Write tests" to "Suggest and write tests for the code I am working on.",
        "Summarize recent commits" to "Summarize what changed in the last few commits and why it matters.",
    )

    private fun font(p: Palette): String = buildString {
        p.fontFamily?.takeIf { it.isNotBlank() }?.let { append(" font-family: \"").append(it.replace("\"", "")).append("\";") }
        if (p.fontSize > 0) append(" font-size: ${p.fontSize}pt;")
    }

    private fun welcome(p: Palette): String {
        val chips = SUGGESTIONS.indices.joinToString("") { i ->
            "<table cellpadding=\"6\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.card}\">" +
                "<a href=\"suggest:$i\"><font color=\"#${p.accent}\">&#10022;&nbsp; ${MarkdownHtml.escape(SUGGESTIONS[i].first)}</font></a>" +
                "</td></tr></table>" + spacer(4)
        }
        return "<table width=\"100%\" cellpadding=\"14\" cellspacing=\"0\"><tr><td>" +
            "<font size=\"+2\" color=\"#${p.accent}\"><b>&#10022; Hi, I'm Kiro</b></font><br>" +
            "<font size=\"+1\">What shall we build today?</font><br><br>" +
            chips + "<br><br>" +
            "<font color=\"#${p.muted}\">" +
            "Enter sends &middot; Shift+Enter adds a line &middot; <b>/</b> commands &middot; <b>@</b> files and skills<br>" +
            "Select code in the editor and press Ctrl+Alt+K to attach it. Long pastes become chips too.<br>" +
            "File names in answers open with Ctrl+click. You review every diff before Kiro changes a file." +
            "</font></td></tr></table>"
    }

    private fun user(b: ChatBlock.User, p: Palette): String {
        val text = if (b.text.isBlank()) "" else "<br><font size=\"1\">&nbsp;</font><br>${MarkdownHtml.escape(b.text)}"
        val cards = b.attachments.joinToString("") { spacer(6) + attachmentCard(it, p) }
        return "<table width=\"100%\" cellpadding=\"8\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.card}\">" +
            "<font color=\"#${p.accent}\"><b>You</b></font>$text$cards</td></tr></table>"
    }

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

    private fun agent(index: Int, b: ChatBlock.Agent, p: Palette, linker: FileLinker?, linkVersion: Int): String {
        val key = Objects.hash(b.text.length, p, linkVersion, index, linker != null)
        b.cachedHtml?.let { if (b.cacheKey == key) return it }

        var md = MarkdownHtml.render(b.text.toString(), linker)
        md = md.replace("<table>", "<table border=\"1\" bordercolor=\"#${p.muted}\" cellpadding=\"5\" cellspacing=\"0\">")
        val body = codeBlocks(md, p, index)
        val html = "<table width=\"100%\" cellpadding=\"0\" cellspacing=\"0\"><tr>" +
            "<td width=\"3\" bgcolor=\"#${p.accent}\"><font size=\"1\">&nbsp;</font></td><td width=\"10\"><font size=\"1\">&nbsp;</font></td><td>" +
            "<font color=\"#${p.accent}\"><b>&#10022; Kiro</b></font>&nbsp;&nbsp;" +
            "<a href=\"copy:$index\"><font size=\"-1\">Copy</font></a>" +
            "<table cellpadding=\"0\" cellspacing=\"0\"><tr><td height=\"4\"></td></tr></table>" +
            body + "</td></tr></table>"
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
        return "&nbsp;&nbsp;<font color=\"#$color\">&#9679;</font>&nbsp;<font color=\"#${p.muted}\">${MarkdownHtml.escape(b.title)}</font>&nbsp;" +
            "<font color=\"#$color\"><b>$mark</b></font><br>"
    }

    private fun note(b: ChatBlock.Note, p: Palette) =
        "<font color=\"#${p.muted}\"><i>${MarkdownHtml.escape(b.text)}</i></font>"

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
            val header = "<font size=\"-2\" color=\"#${p.muted}\">${(lang ?: "code").uppercase()}</font>&nbsp;&nbsp;" +
                "<a href=\"copycode:$blockIndex:$index\"><font size=\"-2\">Copy</font></a><br>"
            "<table width=\"100%\" cellpadding=\"8\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.code}\">" +
                header + "<font color=\"#${p.fg}\"><tt>${codeHtml(raw, lang, p)}</tt></font></td></tr></table>" + spacer(6)
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
                DiffText.Kind.HEADER -> Triple(p.code, p.muted, "")
                DiffText.Kind.CONTEXT -> Triple(p.code, p.fg, "")
            }
            val text = codeHtml(l.text, null, p).ifEmpty { "&nbsp;" }
            "<tr><td width=\"14\" valign=\"top\" bgcolor=\"#$bg\"><font color=\"#$fg\"><tt><b>$mark</b></tt></font></td>" +
                "<td bgcolor=\"#$bg\"><font color=\"#${if (l.kind == DiffText.Kind.CONTEXT) p.fg else fg}\"><tt>$text</tt></font></td></tr>"
        }
        return "<table width=\"100%\" cellpadding=\"8\" cellspacing=\"0\"><tr><td bgcolor=\"#${p.code}\">$header" +
            "<table width=\"100%\" cellpadding=\"2\" cellspacing=\"0\">$rows</table></td></tr></table>" + spacer(6)
    }

    /** commonmark escapes `& < > "` inside code; the highlighter wants the raw text. */
    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    private fun spacer(height: Int) =
        "<table cellpadding=\"0\" cellspacing=\"0\"><tr><td height=\"$height\"></td></tr></table>"
}
