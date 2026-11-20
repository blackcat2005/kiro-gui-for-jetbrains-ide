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
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
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

    /** Jumps to the end of the chat, also after the next render (used when the user sends a message while scrolled up). */
    fun scrollToEnd()

    companion object {
        /**
         * The web view if [useWeb] and the IDE supports it; Swing otherwise, also if the browser fails to start.
         * Starting Chromium takes a second or more on the UI thread (the first one also loads the whole engine), so the
         * browser is only created once the chat is on screen: several restored chats or a hidden tool window no
         * longer freeze the IDE while it loads.
         */
        fun create(parent: Disposable, useWeb: Boolean): TranscriptView =
            if (useWeb && jcefSupported()) LazyTranscript(parent) else SwingTranscript()

        /** False when the IDE runtime has no JCEF at all (the JBCefApp class itself may be missing). */
        private fun jcefSupported(): Boolean =
            try {
                JBCefApp.isSupported()
            } catch (e: Throwable) {
                com.intellij.openapi.diagnostic.Logger.getInstance(TranscriptView::class.java).info("JCEF unavailable, using Swing: $e")
                false
            }

        internal fun createNow(parent: Disposable): TranscriptView {
            try {
                return WebTranscript(parent)
            } catch (e: Throwable) {
                com.intellij.openapi.diagnostic.Logger.getInstance(TranscriptView::class.java).warn("Web chat view unavailable, using Swing", e)
            }
            return SwingTranscript()
        }
    }
}

/** A placeholder that becomes the real view the first time it is showing, a moment after the IDE has painted. */
class LazyTranscript(private val parent: Disposable) : TranscriptView {
    private class Args(val blocks: List<ChatBlock>, val p: Palette, val linker: FileLinker?, val linkVersion: Int)

    private val holder = javax.swing.JPanel(java.awt.BorderLayout())
    private var inner: TranscriptView? = null
    private var starting = false
    private var latest: Args? = null
    private var gone = false

    override val component: JComponent get() = holder
    override var onLink: (String, Boolean) -> Unit = { _, _ -> }
        set(value) {
            field = value
            inner?.onLink = value
        }

    init {
        Disposer.register(parent) { gone = true }
        holder.addHierarchyListener {
            if (inner == null && !starting && holder.isShowing) {
                starting = true
                SwingUtilities.invokeLater(::materialize)
            }
        }
    }

    private fun materialize() {
        if (gone) return
        val view = TranscriptView.createNow(parent)
        view.onLink = onLink
        inner = view
        holder.add(view.component, java.awt.BorderLayout.CENTER)
        holder.revalidate()
        holder.repaint()
        latest?.let { view.render(it.blocks, it.p, it.linker, it.linkVersion) }
        latest = null
    }

    override fun scrollToEnd() {
        inner?.scrollToEnd()
    }

    override fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker?, linkVersion: Int) {
        val view = inner
        if (view != null) view.render(blocks, p, linker, linkVersion)
        else {
            latest = Args(blocks, p, linker, linkVersion)
            holder.background = Color.decode("#${p.bg}") // no white flash while the browser starts
        }
    }
}

/**
 * The classic view: Swing's HTML 3.2 renderer ([TranscriptHtml]). One small HTML pane per chat block instead of one for
 * the whole chat: Swing lays out a document on the UI thread and that grows much faster than linearly with its size, so a
 * streamed answer used to cost as much as the entire chat. Now only the pane of the block that changed is touched, and
 * the others keep their layout.
 */
class SwingTranscript : TranscriptView {
    /** One block: its pane, the page it shows, and the height it had at [width]. */
    private class Row(val pane: JEditorPane) {
        var page: String = ""
        var width = -1
        var height = 0
    }

    private val rows = ArrayList<Row>()
    private var hiddenNote: Row? = null

    /** The rows stacked, each as tall as its text needs at the current width. Heights are cached per row. */
    private val list = object : javax.swing.JPanel(null), javax.swing.Scrollable {
        private fun all(): List<Row> = listOfNotNull(hiddenNote) + rows

        private fun measure(w: Int): Int {
            var y = INSET
            for (r in all()) {
                if (r.width != w) {
                    r.pane.setSize(w, Short.MAX_VALUE.toInt())
                    r.height = r.pane.preferredSize.height
                    r.width = w
                }
                y += r.height
            }
            return y + INSET
        }

        override fun doLayout() {
            val w = width.coerceAtLeast(1)
            var y = INSET
            measure(w)
            for (r in all()) {
                r.pane.setBounds(0, y, w, r.height)
                y += r.height
            }
        }

        override fun getPreferredSize(): java.awt.Dimension {
            val w = (parent?.width ?: 400).coerceAtLeast(1)
            return java.awt.Dimension(w, measure(w))
        }

        override fun getPreferredScrollableViewportSize() = preferredSize
        override fun getScrollableUnitIncrement(r: java.awt.Rectangle, o: Int, d: Int) = JBUI.scale(24)
        override fun getScrollableBlockIncrement(r: java.awt.Rectangle, o: Int, d: Int) = (r.height * 0.9).toInt()
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
    private val scroll = JBScrollPane(list).apply { border = JBUI.Borders.empty() }

    override val component: JComponent get() = scroll
    override var onLink: (String, Boolean) -> Unit = { _, _ -> }

    /** Set by [scrollToEnd]; the next [apply] then ends at the bottom whatever the scroll position was. */
    private var forceEnd = false

    override fun scrollToEnd() {
        forceEnd = true
        val bar = scroll.verticalScrollBar
        SwingUtilities.invokeLater { bar.value = bar.maximum }
    }

    private fun newPane(): JEditorPane = JEditorPane().apply {
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

    private data class Args(val blocks: List<ChatBlock>, val p: Palette, val linker: FileLinker?, val linkVersion: Int)

    /** The newest render asked for that has not been drawn yet. */
    private var waiting: Args? = null
    private var lastDrawn = 0L
    private var background: String? = null

    /** True from the moment a draw is handed to the background thread until its result has been applied. */
    private var computing = false

    // The HTML is built and parsed on a background thread (Swing's own work on the UI thread is then only to attach the
    // ready document to a pane and measure it). One thread, newest request wins.
    private val worker = com.intellij.util.concurrency.AppExecutorUtil.createBoundedApplicationPoolExecutor("Kiro transcript", 1)
    private val docKit = HTMLEditorKitBuilder().withWordWrapViewFactory().build()

    // Nothing is drawn while the chat is not on screen (restored chats in background tabs cost nothing), and while it
    // streams at most about ten times a second.
    private val later = javax.swing.Timer(MIN_GAP_MS) { drawWaiting() }.apply { isRepeats = false }

    init {
        scroll.addHierarchyListener { if (waiting != null && scroll.isShowing) schedule() }
    }

    override fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker?, linkVersion: Int) {
        waiting = Args(blocks, p, linker, linkVersion)
        if (scroll.isShowing) schedule()
    }

    private fun schedule() {
        if (later.isRunning || computing) return
        val wait = MIN_GAP_MS - (System.currentTimeMillis() - lastDrawn)
        if (wait <= 0) drawWaiting() else {
            later.initialDelay = wait.toInt()
            later.start()
        }
    }

    private fun drawWaiting() {
        if (computing) return
        val a = waiting ?: return
        if (!scroll.isShowing) return
        waiting = null
        compute(a)
    }

    /** What the background thread works on: blocks the UI thread can go on changing are copied first. */
    private fun snapshot(shown: List<ChatBlock>): List<ChatBlock> = shown.map { b ->
        when (b) {
            is ChatBlock.Agent -> ChatBlock.Agent(StringBuilder(b.text), b.time).also {
                it.cachedHtml = b.cachedHtml
                it.cacheKey = b.cacheKey
            }
            is ChatBlock.Tool -> ChatBlock.Tool(b.id, b.title, b.status)
            else -> b
        }
    }

    /** A page parsed on the background thread, ready to be attached to a pane. */
    private class Ready(val page: String, val doc: javax.swing.text.Document?)

    private fun parse(page: String): javax.swing.text.Document {
        val doc = docKit.createDefaultDocument()
        doc.putProperty("IgnoreCharsetDirective", true)
        docKit.read(java.io.StringReader(page), doc, 0)
        return doc
    }

    private fun compute(a: Args) {
        computing = true
        val (blocks, p, linker, linkVersion) = a
        // Only the tail is drawn: a very long chat would still make the first draw slow. The web view has no limit.
        val hidden = (blocks.size - MAX_BLOCKS).coerceAtLeast(0)
        val shown = if (hidden > 0) blocks.subList(hidden, blocks.size) else blocks
        val originals = ArrayList(shown)
        val copies = snapshot(shown)
        val oldPages = rows.map { it.page }
        val oldNote = hiddenNote?.page
        worker.execute {
            try {
                val pages = TranscriptHtml.renderRows(copies, p, linker, linkVersion).map { TranscriptHtml.page(p, it, MARGIN) }
                val ready = pages.mapIndexed { i, page -> Ready(page, if (i < oldPages.size && oldPages[i] == page) null else parse(page)) }
                val note = if (hidden > 0) {
                    val page = TranscriptHtml.page(
                        p,
                        "<p><font size=\"-1\" color=\"#${p.muted}\">$hidden earlier messages hidden in this view " +
                            "(turn on the rounded chat view in settings to see everything)</font></p>",
                        MARGIN,
                    )
                    Ready(page, if (page == oldNote) null else parse(page))
                } else null
                SwingUtilities.invokeLater { apply(p, ready, note, originals, copies) }
            } catch (e: Throwable) {
                com.intellij.openapi.diagnostic.Logger.getInstance(SwingTranscript::class.java).warn("Could not draw the chat", e)
                SwingUtilities.invokeLater { finished() }
            }
        }
    }

    private fun finished() {
        computing = false
        lastDrawn = System.currentTimeMillis()
        if (waiting != null && scroll.isShowing) schedule()
    }

    /** UI thread: attaches what the background thread prepared. Rows whose page did not change keep their layout. */
    private fun apply(p: Palette, ready: List<Ready>, note: Ready?, originals: List<ChatBlock>, copies: List<ChatBlock>) {
        try {
            // Finished answers keep their rendered HTML for next time.
            for (i in originals.indices) {
                val o = originals[i]
                val c = copies[i]
                if (o is ChatBlock.Agent && c is ChatBlock.Agent && c.cachedHtml != null) {
                    o.cachedHtml = c.cachedHtml
                    o.cacheKey = c.cacheKey
                }
            }
            val bar = scroll.verticalScrollBar
            val atBottom = forceEnd || bar.value + bar.visibleAmount >= bar.maximum - 8
            val previous = bar.value
            val bg = Color.decode("#${p.bg}")
            if (background != p.bg) {
                background = p.bg
                list.background = bg
            }
            var changed = false

            if (note != null) {
                val row = hiddenNote ?: Row(newPane()).also { hiddenNote = it; list.add(it.pane); changed = true }
                changed = show(row, note, bg) || changed
            } else {
                hiddenNote?.let {
                    list.remove(it.pane)
                    hiddenNote = null
                    changed = true
                }
            }
            for (i in ready.indices) {
                val row = if (i < rows.size) rows[i] else Row(newPane()).also { rows.add(it); list.add(it.pane); changed = true }
                changed = show(row, ready[i], bg) || changed
            }
            while (rows.size > ready.size) {
                list.remove(rows.removeAt(rows.lastIndex).pane)
                changed = true
            }
            if (changed) {
                list.revalidate()
                list.repaint()
                SwingUtilities.invokeLater { bar.value = if (atBottom) bar.maximum else previous }
                forceEnd = false
            }
        } finally {
            finished()
        }
    }

    /** Attaches [r]'s document to [row] unless the row already shows that page; true if it changed. */
    private fun show(row: Row, r: Ready, bg: Color): Boolean {
        val doc = r.doc ?: return false
        row.page = r.page
        row.pane.background = bg
        row.pane.document = doc
        row.width = -1
        return true
    }

    private companion object {
        const val MAX_BLOCKS = 150
        const val MIN_GAP_MS = 100
        const val INSET = 12
        const val MARGIN = "0px 12px 0px 12px"
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

    /** What the page currently shows (rows, CSS, background); only differences are sent on the next render. */
    private var sentRows: List<String> = emptyList()
    private var sentCss: String? = null
    private var sentBg: String? = null

    /** The newest render asked for before the page finished loading. */
    private var pending: Pair<Palette, List<String>>? = null
    private var cssFor: Palette? = null
    private var cssText: String = ""

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

    override fun scrollToEnd() {
        if (loaded) browser.cefBrowser.executeJavaScript("scrollEnd()", "", 0)
    }

    override fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker?, linkVersion: Int) {
        val rows = WebHtml.renderRows(blocks, p, linker, linkVersion)
        if (!loaded) {
            pending = p to rows
            return
        }
        push(p, rows)
    }

    /** Sends only the rows that differ from what the page has, and the CSS / background only when they changed. */
    private fun push(p: Palette, rows: List<String>) {
        if (cssFor != p) {
            cssText = WebHtml.css(p)
            cssFor = p
        }
        if (sentBg != p.bg) {
            sentBg = p.bg
            try {
                browser.setPageBackgroundColor("#${p.bg}")
            } catch (_: Throwable) {
            }
        }
        val changed = buildJsonArray {
            for (i in rows.indices) {
                if (i >= sentRows.size || sentRows[i] != rows[i]) {
                    add(buildJsonArray { add(JsonPrimitive(i)); add(JsonPrimitive(rows[i])) })
                }
            }
        }
        val cssChanged = sentCss != cssText
        if (changed.isEmpty() && rows.size == sentRows.size && !cssChanged) return
        sentRows = rows
        sentCss = cssText
        val css = if (cssChanged) JsonPrimitive(cssText).toString() else "null"
        browser.cefBrowser.executeJavaScript("setRows($css,${rows.size},$changed)", "", 0)
    }

    private companion object {
        val SHELL = """
<!doctype html><html><head><meta charset="utf-8"><style id="theme"></style></head><body><div id="root"></div>
<script>
// The IDE already worked out which rows changed (one div per chat block), so nothing is diffed or re-parsed here:
// css is null when unchanged, count is the new number of rows, changed is a list of [index, html].
var forceEnd = false;
function scrollEnd() {
  forceEnd = true;
  var e = document.scrollingElement || document.documentElement;
  e.scrollTop = e.scrollHeight;
}
function setRows(css, count, changed) {
  if (css !== null) document.getElementById('theme').textContent = css;
  var el = document.scrollingElement || document.documentElement;
  var atBottom = forceEnd || (el.scrollHeight - el.scrollTop - el.clientHeight) < 48;
  forceEnd = false;
  var top = el.scrollTop;
  var root = document.getElementById('root');
  for (var k = 0; k < changed.length; k++) {
    var i = changed[k][0], d = document.createElement('div');
    d.innerHTML = changed[k][1];
    if (i < root.children.length) root.replaceChild(d, root.children[i]); else root.appendChild(d);
  }
  while (root.children.length > count) root.removeChild(root.lastChild);
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
