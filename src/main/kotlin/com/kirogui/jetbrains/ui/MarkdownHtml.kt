package com.kirogui.jetbrains.ui

import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/** Markdown -> HTML for the Swing HTML view. Raw HTML in the source is escaped, never rendered. */
object MarkdownHtml {
    private val parser: Parser = Parser.builder().build()
    private val renderer: HtmlRenderer = HtmlRenderer.builder().escapeHtml(true).build()

    fun render(markdown: String): String {
        val doc: Node = parser.parse(markdown)
        return renderer.render(doc)
    }

    fun escape(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
}
