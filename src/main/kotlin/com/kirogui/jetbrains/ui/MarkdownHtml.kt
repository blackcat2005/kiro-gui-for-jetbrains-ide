package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.kiro.FileLinker
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import java.net.URLDecoder
import java.net.URLEncoder

/** Markdown -> HTML for the Swing HTML view. Raw HTML in the source is escaped, never rendered. */
object MarkdownHtml {
    private val extensions = listOf(TablesExtension.create())
    private val parser: Parser = Parser.builder().extensions(extensions).build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().extensions(extensions).escapeHtml(true).build()

    const val FILE_SCHEME = "kirofile:"

    /** With a [linker], file names in the text (and in `inline code`) become `kirofile:` links. */
    fun render(markdown: String, linker: FileLinker? = null): String {
        val doc: Node = parser.parse(markdown)
        if (linker != null) FileLinkVisitor(linker).also { doc.accept(it); it.apply() }
        return renderer.render(doc)
    }

    /** The raw text of each code block, in document order (matches the order of `<pre>` in [render]). */
    fun codeBlocks(markdown: String): List<String> {
        val out = ArrayList<String>()
        parser.parse(markdown).accept(object : AbstractVisitor() {
            override fun visit(b: FencedCodeBlock) {
                out.add(b.literal.trimEnd('\n'))
            }

            override fun visit(b: IndentedCodeBlock) {
                out.add(b.literal.trimEnd('\n'))
            }
        })
        return out
    }

    fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")

    // ---------- file links ----------

    /** "target" and optional 1-based line, as carried in a `kirofile:` link. */
    data class FileRef(val target: String, val line: Int?)

    fun fileLink(target: String, line: Int?): String =
        FILE_SCHEME + URLEncoder.encode(target + "#" + (line ?: ""), Charsets.UTF_8)

    fun parseFileLink(href: String): FileRef? {
        if (!href.startsWith(FILE_SCHEME)) return null
        val decoded = URLDecoder.decode(href.removePrefix(FILE_SCHEME), Charsets.UTF_8)
        val target = decoded.substringBeforeLast('#')
        if (target.isEmpty()) return null
        return FileRef(target, decoded.substringAfterLast('#', "").toIntOrNull())
    }

    /** A file name with an extension, optionally followed by `:12`, `:12-20` or `#L12`. */
    private val PATH = Regex(
        """(?<![\w@./\\:-])((?:[A-Za-z]:[\\/])?(?:[\w.\-~]+[\\/])*[\w\-~][\w.\-~]*\.[A-Za-z][A-Za-z0-9]{0,9})""" +
            """(?::(\d{1,6})(?:[-–]\d{1,6})?|#L(\d{1,6}))?(?![\w/\\])""",
    )

    private class FileLinkVisitor(private val linker: FileLinker) : AbstractVisitor() {
        private val changes = ArrayList<() -> Unit>()

        fun apply() = changes.forEach { it() }

        override fun visit(link: Link) {
            // Already a link: leave it (and what is inside it) alone.
        }

        override fun visit(code: Code) {
            val m = PATH.matchEntire(code.literal.trim()) ?: return
            val target = linker.resolve(m.groupValues[1]) ?: return
            val dest = fileLink(target, lineOf(m))
            changes.add {
                val link = Link(dest, null)
                code.insertBefore(link)
                link.appendChild(code)
            }
        }

        override fun visit(text: Text) {
            val literal = text.literal
            val matches = PATH.findAll(literal).toList()
            if (matches.isEmpty()) return
            val pieces = ArrayList<Node>()
            var last = 0
            for (m in matches) {
                val target = linker.resolve(m.groupValues[1]) ?: continue
                if (m.range.first > last) pieces.add(Text(literal.substring(last, m.range.first)))
                pieces.add(Link(fileLink(target, lineOf(m)), null).also { it.appendChild(Text(m.value)) })
                last = m.range.last + 1
            }
            if (pieces.isEmpty()) return
            if (last < literal.length) pieces.add(Text(literal.substring(last)))
            changes.add {
                pieces.forEach { text.insertBefore(it) }
                text.unlink()
            }
        }

        private fun lineOf(m: MatchResult): Int? = (m.groupValues[2].ifEmpty { m.groupValues[3] }).toIntOrNull()
    }
}
