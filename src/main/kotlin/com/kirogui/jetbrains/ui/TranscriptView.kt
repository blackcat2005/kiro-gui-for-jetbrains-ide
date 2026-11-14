package com.kirogui.jetbrains.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.kiro.FileLinker
import kotlinx.serialization.json.JsonPrimitive
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import java.awt.Color
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.SwingUtilities
import javax.swing.event.HyperlinkEvent

/** Where the chat transcript is drawn: an embedded browser when the IDE has one, else Swing's HTML view. */
interface TranscriptView {
    val component: JComponent

    /** A link was activated: its `href` and whether it was a Ctrl/Cmd click (the web view always says yes). */
    var onLink: (href: String, ctrl: Boolean) -> Unit

    fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker?, linkVersion: Int)

    companion object {
        /** The web view if [useWeb] and the IDE supports it; Swing otherwise, also if the browser fails to start. */
        fun create(parent: Disposable, useWeb: Boolean): TranscriptView {
            if (useWeb && JBCefApp.isSupported()) {
                try {
                    return WebTranscript(parent)
                } catch (e: Throwable) {
                    com.intellij.openapi.diagnostic.Logger.getInstance(TranscriptView::class.java).warn("Web chat view unavailable, using Swing", e)
                }
            }
            return SwingTranscript()
        }
    }
}

/** The classic view: Swing's HTML 3.2 renderer ([TranscriptHtml]). */
class SwingTranscript : TranscriptView {
    private val pane = JEditorPane().apply {
        isEditable = false
        editorKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()
        addHyperlinkListener { e ->
            val href = e.description.orEmpty()
            when (e.eventType) {
                HyperlinkEvent.EventType.ENTERED -> toolTipText = com.kirogui.jetbrains.ui.MarkdownHtml.parseFileLink(href)
                    ?.let { "Ctrl+click to open ${it.target}" + (it.line?.let { l -> ":$l" } ?: "") }
                HyperlinkEvent.EventType.EXITED -> toolTipText = null
                HyperlinkEvent.EventType.ACTIVATED -> {
                    val click = e.inputEvent as? java.awt.event.MouseEvent
                    onLink(e.url?.toString() ?: href, click != null && (click.isControlDown || click.isMetaDown))
                }
                else -> Unit
            }
        }
    }
    private val scroll = JBScrollPane(pane).apply { border = JBUI.Borders.empty() }

    override val component: JComponent get() = scroll
    override var onLink: (String, Boolean) -> Unit = { _, _ -> }

    override fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker?, linkVersion: Int) {
        val bar = scroll.verticalScrollBar
        val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 8
        val previous = bar.value
        pane.background = Color.decode("#${p.bg}")
        pane.text = TranscriptHtml.render(blocks, p, linker, linkVersion)
        SwingUtilities.invokeLater { bar.value = if (atBottom) bar.maximum else previous }
    }
}

/**
 * The chat in an embedded Chromium (JCEF): rounded cards, hairlines, a timeline ([WebHtml]). The page is a fixed shell
 * loaded once; every render sends it the new CSS and body, and it keeps the scroll position unless you were at the end.
 */
class WebTranscript(parent: Disposable) : TranscriptView {
    private val browser = JBCefBrowser()
    private val query = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private var loaded = false
    private var pending: Pair<String, String>? = null
    private var shown: Pair<String, String>? = null

    override val component: JComponent get() = browser.component
    override var onLink: (String, Boolean) -> Unit = { _, _ -> }

    init {
        Disposer.register(parent, browser)
        Disposer.register(parent, query)
        browser.setProperty(JBCefBrowserBase.Properties.NO_CONTEXT_MENU, true)
        query.addHandler { href ->
            SwingUtilities.invokeLater { onLink(href, true) }
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(b: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                b.executeJavaScript("window.kiroLink=function(h){${query.inject("h")}};", b.url, 0)
                SwingUtilities.invokeLater {
                    loaded = true
                    pending?.let { push(it.first, it.second) }
                    pending = null
                }
            }
        }, browser.cefBrowser)
        // While the window is dragged, Chromium's off-screen frame is stretched until it has repainted at the new size,
        // which looks blurry. Once the size settles, ask it for a fresh frame at the exact size.
        val settle = javax.swing.Timer(120) {
            val c = browser.component
            if (c.width > 0 && c.height > 0) {
                try {
                    browser.cefBrowser.wasResized(c.width, c.height)
                } catch (_: Throwable) {
                }
                browser.cefBrowser.executeJavaScript("window.dispatchEvent(new Event('resize'))", "", 0)
                c.repaint()
            }
        }.apply { isRepeats = false }
        browser.component.addComponentListener(object : java.awt.event.ComponentAdapter() {
            override fun componentResized(e: java.awt.event.ComponentEvent) = settle.restart()
        })
        browser.loadHTML(SHELL)
    }

    override fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker?, linkVersion: Int) {
        val css = WebHtml.css(p)
        val html = WebHtml.render(blocks, p, linker, linkVersion)
        try {
            browser.setPageBackgroundColor("#${p.bg}")
        } catch (_: Throwable) {
        }
        if (!loaded) {
            pending = css to html
            return
        }
        push(css, html)
    }

    private fun push(css: String, html: String) {
        if (shown == css to html) return
        shown = css to html
        browser.cefBrowser.executeJavaScript("setPage(${JsonPrimitive(css)},${JsonPrimitive(html)})", "", 0)
    }

    private companion object {
        val SHELL = """
<!doctype html><html><head><meta charset="utf-8"><style id="theme"></style></head><body><div id="root"></div>
<script>
function setPage(css, html) {
  var t = document.getElementById('theme');
  if (t.textContent !== css) t.textContent = css;
  var el = document.scrollingElement || document.documentElement;
  var atBottom = (el.scrollHeight - el.scrollTop - el.clientHeight) < 48;
  var top = el.scrollTop;
  var root = document.getElementById('root');
  // Update only the rows that changed: replacing everything on each streamed chunk made the scroll stutter.
  var box = document.createElement('div');
  box.innerHTML = html;
  var old = root.children, fresh = box.children, i = 0;
  while (i < fresh.length && i < old.length) {
    if (old[i].outerHTML !== fresh[i].outerHTML) root.replaceChild(fresh[i].cloneNode(true), old[i]);
    i++;
  }
  while (root.children.length > fresh.length) root.removeChild(root.lastChild);
  for (; i < fresh.length; i++) root.appendChild(fresh[i].cloneNode(true));
  el.scrollTop = atBottom ? el.scrollHeight : top;
}
document.addEventListener('click', function (e) {
  var a = e.target.closest ? e.target.closest('a') : null;
  if (!a) return;
  e.preventDefault();
  var h = a.getAttribute('href');
  if (h && window.kiroLink) window.kiroLink(h);
});
</script></body></html>
""".trimIndent()
    }
}
