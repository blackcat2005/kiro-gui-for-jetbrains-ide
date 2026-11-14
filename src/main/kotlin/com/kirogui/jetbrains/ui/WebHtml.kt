package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.kiro.FileLinker
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.Objects
import java.util.WeakHashMap

/**
 * The chat as modern HTML and CSS for the embedded browser (JCEF): rounded cards, hairlines, a timeline down the left.
 * Same [ChatBlock]s and [Palette] as [TranscriptHtml], which stays as the fallback for IDEs without JCEF. Links keep
 * the same schemes (`copy:`, `copycode:`, `kirofile:`), so one handler serves both renderers.
 */
object WebHtml {
    private const val STAMP_GAP_MS = 120_000L

    fun css(p: Palette): String {
        val family = p.fontFamily?.takeIf { it.isNotBlank() }?.replace("\"", "")?.let { "\"$it\", " } ?: ""
        val size = if (p.fontSize > 0) p.fontSize else 14
        return """
:root{--fg:#${p.fg};--muted:#${p.muted};--card:#${p.card};--code:#${p.code};--accent:#${p.accent};--ok:#${p.ok};--err:#${p.err};--border:#${p.border};--bg:#${p.bg};--add:#${p.diffAdd};--del:#${p.diffDel};--hunk:#${p.diffHunk}}
*{box-sizing:border-box}
html,body{margin:0;background:var(--bg);color:var(--fg)}
body{font-family:$family-apple-system,"Segoe UI",system-ui,sans-serif;font-size:${size}px;line-height:1.55;padding:6px 16px 28px;-webkit-font-smoothing:antialiased}
::-webkit-scrollbar{width:10px;height:10px;background:transparent}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--border);border-radius:10px;border:2px solid transparent;background-clip:content-box;min-height:36px}
::-webkit-scrollbar-thumb:hover{background:var(--muted);background-clip:content-box}
::-webkit-scrollbar-button,::-webkit-scrollbar-corner{display:none}
a{color:var(--accent);text-decoration:none}
a:hover{text-decoration:underline}
.time{font-size:11px;color:var(--muted);text-align:right;margin:16px 2px 5px}
.user{margin:6px 0}
.bubble{background:var(--card);background:color-mix(in srgb,var(--accent) 13%,var(--card));border:1px solid var(--accent);border:1px solid color-mix(in srgb,var(--accent) 45%,var(--border));border-radius:14px;padding:10px 16px;white-space:pre-wrap;overflow-wrap:anywhere;box-shadow:0 1px 4px rgba(0,0,0,.18)}
.row{position:relative;padding:3px 0 3px 30px}
.row::before{content:"";position:absolute;left:9px;top:0;bottom:0;width:1px;background:var(--border)}
.row .dot{position:absolute;left:5px;top:.78em;width:9px;height:9px;border-radius:50%;background:var(--muted);z-index:1;box-shadow:0 0 0 3px var(--bg)}
.row.agent{padding-top:6px;padding-bottom:6px}
.row.agent .dot{background:var(--accent)}
.row.small{color:var(--muted);padding-top:6px;padding-bottom:6px}
.ok{color:var(--ok)}.err{color:var(--err)}
.body{overflow-wrap:anywhere}
.body>:first-child{margin-top:0}
.body p{margin:0 0 .65em}
.body ul,.body ol{margin:0 0 .65em;padding-left:1.5em}
.body li{margin:.15em 0}
.body h1,.body h2,.body h3,.body h4{margin:.9em 0 .35em;line-height:1.3}
.body h1{font-size:1.35em}.body h2{font-size:1.2em}.body h3{font-size:1.08em}.body h4{font-size:1em}
.body blockquote{margin:.4em 0;padding:.1em 0 .1em 12px;border-left:3px solid var(--border);color:var(--muted)}
.body hr{border:0;border-top:1px solid var(--border);margin:.8em 0}
.body table{border-collapse:separate;border-spacing:0;border:1px solid var(--border);border-radius:8px;overflow:hidden;margin:.5em 0}
.body th,.body td{padding:5px 10px;border-bottom:1px solid var(--border);text-align:left}
.body th{background:var(--card)}
.body tr:last-child td{border-bottom:0}
code{font-family:ui-monospace,"JetBrains Mono",Consolas,monospace;background:var(--code);border-radius:4px;padding:1px 5px;font-size:.9em}
.copy{font-size:11px;color:var(--muted);opacity:.7}
.code{border:1px solid var(--border);border-radius:9px;background:var(--code);margin:.55em 0;overflow:hidden}
.code-h{display:flex;justify-content:space-between;align-items:center;padding:4px 12px;font-size:11px;color:var(--muted);border-bottom:1px solid var(--border)}
.code-h a{color:var(--muted)}
.code pre{margin:0;padding:9px 12px;overflow-x:auto;font-family:ui-monospace,"JetBrains Mono",Consolas,monospace;font-size:.9em;line-height:1.45;white-space:pre}
.diff .dl{display:flex;padding:0 12px;white-space:pre-wrap;font-family:ui-monospace,"JetBrains Mono",Consolas,monospace;font-size:.9em;line-height:1.45}
.diff .mk{width:16px;flex:none;font-weight:bold}
.diff .add{background:var(--add)}.diff .del{background:var(--del)}.diff .hunk{background:var(--hunk)}
.att{margin-top:8px;border:1px solid var(--border);border-radius:8px;background:var(--code);padding:6px 10px;font-size:.92em;white-space:normal}
.att .k{font-size:10px;letter-spacing:.06em;color:var(--muted);margin-right:8px}
.att pre{margin:4px 0 0;font-family:ui-monospace,Consolas,monospace;font-size:.88em;white-space:pre-wrap}
.att img{display:block;margin-top:6px;border-radius:6px;max-width:240px}
.welcome{text-align:center;padding:48px 0 8px}
.welcome .star{font-size:34px;color:var(--accent);line-height:1}
.welcome h2{margin:8px 0 2px;font-size:1.5em}
.welcome .sub{color:var(--muted)}
.chips{text-align:center;margin:18px 0 22px}
.chip{display:inline-block;margin:4px;padding:7px 15px;border:1px solid var(--border);border-radius:999px;background:var(--card);color:var(--fg)}
.chip:hover{border-color:var(--accent);text-decoration:none}
.sect{text-align:center;color:var(--muted);font-size:.78em;letter-spacing:.08em;text-transform:uppercase;margin:0 0 2px}
.sect-sub{text-align:center;color:var(--muted);font-size:.85em;margin:0 0 10px}
.guide{display:block;border:1px solid var(--accent);border-radius:10px;background:var(--card);padding:11px 14px;margin:0 0 10px;color:var(--fg)}
a.guide:hover{text-decoration:none;background:var(--code)}
.guide b{display:block;color:var(--accent);margin-bottom:2px}
.guide.done{border-color:var(--border)}
.guide.done b{color:var(--ok)}
.guide .d{display:block;font-size:.92em;color:var(--muted)}
.tcards{display:grid;grid-template-columns:repeat(auto-fit,minmax(230px,1fr));gap:8px;margin:0 0 22px}
.tcard{display:block;border:1px solid var(--border);border-radius:10px;background:var(--card);padding:10px 12px;color:var(--fg)}
.tcard:hover{border-color:var(--accent);text-decoration:none}
.tcard b{display:block;color:var(--accent);margin-bottom:2px}
.tcard .d{display:block;font-size:.92em}
.tcard .t{display:block;margin-top:6px;color:var(--muted);font-size:.78em;font-family:ui-monospace,"JetBrains Mono",Consolas,monospace}
.hints{text-align:center;color:var(--muted);font-size:.85em;line-height:1.7}
details.event summary{cursor:pointer;list-style:none;outline:none}
details.event summary::-webkit-details-marker{display:none}
.evbody{margin-top:6px;padding:8px 12px;border:1px solid var(--border);border-radius:8px;background:var(--card);white-space:pre-wrap;overflow-wrap:anywhere}
.note{color:var(--muted);font-style:italic;padding:4px 0 4px 30px;white-space:pre-wrap}
""".trimIndent()
    }

    fun render(blocks: List<ChatBlock>, p: Palette, linker: FileLinker? = null, linkVersion: Int = 0): String {
        val sb = StringBuilder()
        if (blocks.isEmpty()) sb.append(welcome(p))
        var lastStamp = 0L
        blocks.forEachIndexed { i, block ->
            val time = when (block) { is ChatBlock.User -> block.time; is ChatBlock.Agent -> block.time; else -> 0L }
            val showTime = time > 0 && (lastStamp == 0L || time - lastStamp >= STAMP_GAP_MS)
            if (showTime) lastStamp = time
            when (block) {
                is ChatBlock.User -> sb.append(user(block, p, showTime))
                is ChatBlock.Agent -> sb.append(agent(i, block, p, linker, linkVersion, showTime))
                is ChatBlock.Tool -> sb.append(tool(block))
                is ChatBlock.Note -> sb.append(note(block))
            }
        }
        return sb.toString()
    }

    private fun esc(s: String) = MarkdownHtml.escape(s)

    private fun stamp(time: Long, show: Boolean): String =
        if (!TranscriptHtml.SHOW_TIME || !show || time <= 0) "" else "<div class=\"time\">${SimpleDateFormat("h:mm a", Locale.US).format(Date(time))}</div>"

    private fun welcome(p: Palette): String {
        return "<div style=\"height:18px\"></div>" + toolCards(p) +
            "<div class=\"hints\">Enter sends &middot; Shift+Enter adds a line &middot; <b>/</b> commands &middot; <b>@</b> files and skills<br>" +
            "Ctrl+Alt+K attaches the selected code. Long pastes become chips.<br>You review every diff before Kiro changes a file.</div>"
    }

    private fun toolCards(p: Palette): String {
        if (p.tools.isEmpty()) return ""
        val cards = p.tools.mapIndexed { i, c ->
            "<a class=\"tcard\" href=\"tool:$i\"><b>${esc(c.title)}</b><span class=\"d\">${esc(c.blurb)}</span>" +
                "<span class=\"t\">${c.tools.joinToString(" &middot; ") { esc(it) }}</span></a>"
        }.joinToString("")
        val banner = when (p.guidance) {
            null -> ""
            true -> "<div class=\"guide done\"><b>&#10003; Kiro has been told to use these tools</b><span class=\"d\">" +
                "The steering file is installed and kept up to date, so it still applies after Kiro compacts a chat.</span></div>"
            false -> "<a class=\"guide\" href=\"guidance:install\"><b>Install Kiro guidance for all Kiro GUI tools</b><span class=\"d\">" +
                "Kiro doesn't know about these tools yet. This adds a short, always-on steering file so it uses them " +
                "(and keeps using them after a compaction).</span></a>"
        }
        return "<div class=\"sect\">What Kiro can do in your IDE</div><div class=\"sect-sub\">Click one to try it</div>" +
            banner + "<div class=\"tcards\">$cards</div>"
    }

    private fun user(b: ChatBlock.User, p: Palette, showTime: Boolean): String {
        if (b.text.startsWith(com.kirogui.jetbrains.jobs.WakePrompt.TAG)) return ideEvent(b.text)
        val cards = b.attachments.joinToString("") { attachment(it, p) }
        return stamp(b.time, showTime) + "<div class=\"user\"><div class=\"bubble\">${esc(b.text)}$cards</div></div>"
    }

    /** A message the IDE sent on Kiro's behalf (a job ended, the debugger stopped): one muted line, the full text on demand. */
    private fun ideEvent(text: String): String {
        val first = text.removePrefix(com.kirogui.jetbrains.jobs.WakePrompt.TAG).trim().lineSequence().firstOrNull().orEmpty()
        val title = if (first.length > 110) first.take(110) + "…" else first
        return "<details class=\"row small event\"><span class=\"dot\"></span><summary>&#9881; IDE: ${esc(title)}</summary>" +
            "<div class=\"evbody\">${esc(text)}</div></details>"
    }

    private fun attachment(a: Attachment, p: Palette): String = "<div class=\"att\">" + when (a) {
        is Attachment.File ->
            "<span class=\"k\">FILE</span><a href=\"${MarkdownHtml.fileLink(a.path, null)}\">${esc(a.path)}</a>"
        is Attachment.Image ->
            "<span class=\"k\">IMAGE</span><b>${esc(a.label)}</b>" + (thumbnail(a)?.let { "<img src=\"$it\">" } ?: "")
        is Attachment.Snippet -> {
            val (head, more) = Attachments.preview(a.code)
            "<span class=\"k\">${if (a.source == null) "PASTED" else "CODE"}</span><b>${esc(a.label)}</b>" +
                "<pre>${CodeHighlighter.highlight(head, a.language.ifEmpty { null }, p.syntax)}</pre>" +
                (if (more > 0) "<div class=\"k\">&hellip; $more more ${if (more == 1) "line" else "lines"}</div>" else "")
        }
    } + "</div>"

    private val thumbs = WeakHashMap<Attachment.Image, String>()

    /** The small thumbnail as a data URI: the page is not loaded from disk, so it can't point at a file. */
    private fun thumbnail(a: Attachment.Image): String? = synchronized(thumbs) {
        thumbs[a] ?: try {
            ImageAttachments.thumbnailUrl(a)
            val bytes = a.thumb?.readBytes() ?: return null
            ("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)).also { thumbs[a] = it }
        } catch (_: Exception) {
            null
        }
    }

    private fun agent(index: Int, b: ChatBlock.Agent, p: Palette, linker: FileLinker?, linkVersion: Int, showTime: Boolean): String {
        val key = Objects.hash(b.text.length, p, linkVersion, index, linker != null, showTime)
        b.cachedWebHtml?.let { if (b.webCacheKey == key) return it }
        val body = codeBlocks(MarkdownHtml.render(b.text.toString(), linker), p, index)
        val html = stamp(b.time, showTime) +
            "<div class=\"row agent\"><span class=\"dot\"></span><div class=\"body\">$body</div>" +
            "<a class=\"copy\" href=\"copy:$index\">Copy</a></div>"
        b.cachedWebHtml = html
        b.webCacheKey = key
        return html
    }

    private fun tool(b: ChatBlock.Tool): String {
        val (mark, cls) = when (b.status) {
            "completed" -> "&#10003;" to "ok"
            "failed" -> "&#10007;" to "err"
            else -> "&hellip;" to ""
        }
        val title = b.title.replace(Regex("@[A-Za-z0-9_-]+/"), "")
        return "<div class=\"row small\"><span class=\"dot ${cls}\" style=\"${dotColor(cls)}\"></span>${esc(title)} <span class=\"$cls\">$mark</span></div>"
    }

    private fun dotColor(cls: String) = when (cls) {
        "ok" -> "background:var(--ok)"
        "err" -> "background:var(--err)"
        else -> ""
    }

    private val exitCode = Regex("exit code (-?\\d+)")

    private fun note(b: ChatBlock.Note): String {
        if (!b.text.startsWith("⚙")) return "<div class=\"note\">${esc(b.text)}</div>"
        val code = exitCode.find(b.text)?.groupValues?.get(1)?.toIntOrNull()
        val cls = when {
            code != null -> if (code == 0) "ok" else "err"
            b.text.contains("failed", true) -> "err"
            else -> ""
        }
        return "<div class=\"row small\"><span class=\"dot\" style=\"${dotColor(cls)}\"></span>${esc(b.text.removePrefix("⚙").trim())}</div>"
    }

    private val codeBlock = Regex("<pre><code(?: class=\"language-([^\"]*)\")?[^>]*>(.*?)</code></pre>", RegexOption.DOT_MATCHES_ALL)

    private fun codeBlocks(html: String, p: Palette, blockIndex: Int): String {
        var n = 0
        return codeBlock.replace(html) { m ->
            val lang = m.groupValues[1].ifEmpty { null }
            val raw = unescape(m.groupValues[2]).trimEnd('\n')
            val index = n++
            if (CodeHighlighter.isDiff(lang)) return@replace diff(raw, p, blockIndex, index)
            "<div class=\"code\"><div class=\"code-h\"><span>${(lang ?: "code").lowercase()}</span>" +
                "<a href=\"copycode:$blockIndex:$index\">Copy</a></div>" +
                "<pre>${CodeHighlighter.highlight(raw, lang, p.syntax)}</pre></div>"
        }
    }

    private fun diff(raw: String, p: Palette, blockIndex: Int, index: Int): String {
        val lines = DiffText.parse(raw)
        val (added, removed) = DiffText.stats(lines)
        val file = DiffText.path(lines)
        val head = "<div class=\"code-h\"><span>diff" + (file?.let { " &middot; <b>${esc(it)}</b>" } ?: "") +
            " &nbsp;<span class=\"ok\">+$added</span> <span class=\"err\">&minus;$removed</span></span>" +
            "<span><a href=\"copycode:$blockIndex:$index\">Copy diff</a> &nbsp; <a href=\"copycode:$blockIndex:$index:new\">Copy new</a></span></div>"
        val rows = lines.joinToString("") { l ->
            val (cls, mark) = when (l.kind) {
                DiffText.Kind.ADD -> "add" to "+"
                DiffText.Kind.DEL -> "del" to "&minus;"
                DiffText.Kind.HUNK -> "hunk" to ""
                else -> "" to ""
            }
            "<div class=\"dl $cls\"><span class=\"mk\">$mark</span><span>${esc(l.text).ifEmpty { "&nbsp;" }}</span></div>"
        }
        return "<div class=\"code diff\">$head<div style=\"padding:4px 0\">$rows</div></div>"
    }

    private fun unescape(s: String) = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")
}
