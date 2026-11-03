package com.kirogui.jetbrains.ui

/** Hex colours (no '#') for source code, chosen per light/dark theme. */
data class SyntaxColors(
    val keyword: String,
    val string: String,
    val comment: String,
    val number: String,
    val type: String,
    val function: String,
    val annotation: String,
    val added: String,
    val removed: String,
) {
    companion object {
        val LIGHT = SyntaxColors("0033B3", "067D17", "8C8C8C", "1750EB", "007A7A", "00627A", "9E880D", "1E7E34", "C62828")
        val DARK = SyntaxColors("CF8E6D", "6AAB73", "7A7E85", "2AACB8", "4DB6AC", "56A8F5", "BBB529", "6AAB73", "FF6B68")
    }
}

/**
 * Turns source code into `<font>`-coloured HTML for Swing's HTML 3.2 renderer. A small scanner rather than the
 * IDE's lexers, so it works the same in every IDE (no language plugin needed), on any thread, and can be tested
 * and previewed outside the IDE. Understands comments, strings, numbers, keywords, types, calls and annotations
 * for the common languages, plus JSON, XML/HTML, YAML-style keys and unified diffs. Unknown languages come back
 * as escaped plain text.
 */
object CodeHighlighter {

    private enum class Kind { DEFAULT, KEYWORD, STRING, COMMENT, NUMBER, TYPE, FUNCTION, ANNOTATION }

    private class Tok(val kind: Kind, val text: String)

    private class Spec(
        val line: List<String> = emptyList(),
        val block: Pair<String, String>? = null,
        /** String delimiters; longer ones first (triple quotes before single). */
        val quotes: List<String> = listOf("\""),
        val keywords: Set<String> = emptySet(),
        val ignoreCase: Boolean = false,
        val annotations: Boolean = true,
        /** `key:` at the start of an identifier run (YAML, TOML) is a key. */
        val identKeys: Boolean = false,
        /** A string followed by `:` is an object key (JSON). */
        val stringKeys: Boolean = false,
    )

    private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }.toSet()

    private val CLIKE_LINES = listOf("//")
    private val CLIKE_BLOCK = "/*" to "*/"

    private val SPECS: Map<String, Spec> by lazy {
        val kotlin = Spec(CLIKE_LINES, CLIKE_BLOCK, listOf("\"\"\"", "\"", "'"), words(
            "abstract actual annotation as break by catch class companion const constructor continue crossinline data delegate do " +
                "dynamic else enum expect external false final finally for fun get if import in infix init inline inner interface " +
                "internal is it lateinit lazy noinline null object open operator out override package private protected public " +
                "reified return sealed set super suspend this throw true try typealias val value var vararg when where while",
        ))
        val java = Spec(CLIKE_LINES, CLIKE_BLOCK, listOf("\"\"\"", "\"", "'"), words(
            "abstract assert boolean break byte case catch char class const continue default do double else enum extends final " +
                "finally float for goto if implements import instanceof int interface long native new null package private protected " +
                "public return short static strictfp super switch synchronized this throw throws transient true false try var void " +
                "volatile while record sealed permits yield",
        ))
        val js = Spec(CLIKE_LINES, CLIKE_BLOCK, listOf("`", "\"", "'"), words(
            "abstract as async await break case catch class const continue debugger declare default delete do else enum export " +
                "extends false finally for from function if implements import in instanceof interface is keyof let namespace new null " +
                "of private protected public readonly return satisfies static super switch this throw true try type typeof undefined " +
                "var void while with yield",
        ))
        val python = Spec(listOf("#"), null, listOf("\"\"\"", "'''", "\"", "'"), words(
            "False None True and as assert async await break case class continue def del elif else except finally for from global " +
                "if import in is lambda match nonlocal not or pass raise return self cls try while with yield",
        ))
        val go = Spec(CLIKE_LINES, CLIKE_BLOCK, listOf("`", "\"", "'"), words(
            "break case chan const continue default defer else fallthrough false for func go goto if import interface map nil " +
                "package range return select struct switch true type var",
        ))
        val rust = Spec(CLIKE_LINES, CLIKE_BLOCK, listOf("\"", "'"), words(
            "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub " +
                "ref return self Self static struct super trait true type unsafe use where while",
        ))
        val cfamily = Spec(CLIKE_LINES, CLIKE_BLOCK, listOf("\"", "'"), words(
            "abstract as auto bool break case catch char class const constexpr continue default delete do double else enum explicit " +
                "extern false final float for foreach friend goto if in inline int interface internal is long namespace new noexcept " +
                "null nullptr object operator override private protected public readonly register return sealed short signed sizeof " +
                "static string struct switch template this throw true try typedef typename union unsigned using var virtual void " +
                "volatile while async await get set",
        ))
        val shell = Spec(listOf("#"), null, listOf("\"", "'", "`"), words(
            "alias case cd declare do done echo elif else esac exit export fi for function if in local readonly return select set " +
                "source then time unset until while",
        ), annotations = false)
        val sql = Spec(listOf("--"), CLIKE_BLOCK, listOf("'", "\""), words(
            "all alter and as asc between by case check constraint count create cross database default delete desc distinct drop " +
                "else end exists foreign from full group having in index inner insert into is join key left like limit max min not " +
                "null offset on or order outer primary references right select set sum table then union unique update values view " +
                "when where with avg",
        ), ignoreCase = true, annotations = false)
        val yaml = Spec(listOf("#"), null, listOf("\"", "'"), words("true false null yes no on off"), identKeys = true, annotations = false)
        val json = Spec(emptyList(), null, listOf("\""), words("true false null"), stringKeys = true, annotations = false)
        val ruby = Spec(listOf("#"), null, listOf("\"", "'"), words(
            "alias and begin break case class def defined do else elsif end ensure false for if in module next nil not or redo " +
                "rescue retry return self super then true undef unless until when while yield",
        ))
        val swift = Spec(CLIKE_LINES, CLIKE_BLOCK, listOf("\"\"\"", "\"", "'"), words(
            "actor as associatedtype async await break case catch class continue default defer deinit do else enum extension false " +
                "fileprivate for func guard if import in init inout internal is let nil open operator override private protocol public " +
                "return self static struct subscript super switch throw throws true try typealias var where while",
        ))
        mapOf(
            "kotlin" to kotlin, "java" to java, "scala" to java, "groovy" to java,
            "js" to js, "ts" to js, "go" to go, "rust" to rust, "swift" to swift,
            "c" to cfamily, "cpp" to cfamily, "csharp" to cfamily, "dart" to cfamily, "php" to cfamily,
            "python" to python, "sh" to shell, "sql" to sql, "yaml" to yaml, "json" to json, "ruby" to ruby,
        )
    }

    private val ALIASES = mapOf(
        "kt" to "kotlin", "kts" to "kotlin", "gradle" to "groovy",
        "javascript" to "js", "jsx" to "js", "mjs" to "js", "typescript" to "ts", "tsx" to "ts",
        "py" to "python", "python3" to "python",
        "bash" to "sh", "shell" to "sh", "zsh" to "sh", "console" to "sh", "powershell" to "sh", "ps1" to "sh",
        "yml" to "yaml", "toml" to "yaml", "ini" to "yaml", "properties" to "yaml",
        "c++" to "cpp", "cc" to "cpp", "h" to "c", "hpp" to "cpp", "cs" to "csharp", "rs" to "rust", "golang" to "go",
        "jsonc" to "json", "json5" to "json", "rb" to "ruby", "html" to "xml", "svg" to "xml", "xhtml" to "xml",
        "patch" to "diff",
    )

    /** True for a fence that holds a unified diff (`diff`, `patch`). */
    fun isDiff(lang: String?): Boolean =
        lang?.trim()?.lowercase()?.substringBefore(' ')?.let { ALIASES[it] ?: it } == "diff"

    /** [lang] is the fence's info string (e.g. `kotlin`); null/unknown gives escaped plain text. */
    fun highlight(code: String, lang: String?, c: SyntaxColors): String {
        val key = lang?.trim()?.lowercase()?.substringBefore(' ')?.let { ALIASES[it] ?: it }
        val tokens = when {
            key == null -> return escape(code)
            key == "diff" -> return highlightDiff(code, c)
            key == "xml" -> xml(code)
            else -> SPECS[key]?.let { scan(code, it) } ?: return escape(code)
        }
        return render(tokens, c)
    }

    // ---------- output ----------

    private fun render(tokens: List<Tok>, c: SyntaxColors): String {
        val sb = StringBuilder()
        for (t in tokens) {
            val color = when (t.kind) {
                Kind.DEFAULT -> null
                Kind.KEYWORD -> c.keyword
                Kind.STRING -> c.string
                Kind.COMMENT -> c.comment
                Kind.NUMBER -> c.number
                Kind.TYPE -> c.type
                Kind.FUNCTION -> c.function
                Kind.ANNOTATION -> c.annotation
            }
            val text = escape(t.text)
            when {
                color == null -> sb.append(text)
                t.kind == Kind.COMMENT -> sb.append("<font color=\"#").append(color).append("\"><i>").append(text).append("</i></font>")
                else -> sb.append("<font color=\"#").append(color).append("\">").append(text).append("</font>")
            }
        }
        return sb.toString()
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    // ---------- generic scanner ----------

    private fun scan(code: String, spec: Spec): List<Tok> {
        val out = ArrayList<Tok>()
        val plain = StringBuilder()
        fun flush() {
            if (plain.isNotEmpty()) {
                out.add(Tok(Kind.DEFAULT, plain.toString()))
                plain.setLength(0)
            }
        }
        fun emit(kind: Kind, text: String) {
            flush()
            out.add(Tok(kind, text))
        }

        var i = 0
        val n = code.length
        while (i < n) {
            val ch = code[i]

            val lineStart = spec.line.firstOrNull { code.startsWith(it, i) }
            if (lineStart != null) {
                val end = code.indexOf('\n', i).let { if (it < 0) n else it }
                emit(Kind.COMMENT, code.substring(i, end))
                i = end
                continue
            }
            val block = spec.block
            if (block != null && code.startsWith(block.first, i)) {
                val close = code.indexOf(block.second, i + block.first.length)
                val end = if (close < 0) n else close + block.second.length
                emit(Kind.COMMENT, code.substring(i, end))
                i = end
                continue
            }
            val quote = spec.quotes.firstOrNull { code.startsWith(it, i) }
            if (quote != null) {
                val end = stringEnd(code, i, quote)
                val text = code.substring(i, end)
                val isKey = spec.stringKeys && nextNonSpace(code, end) == ':'
                emit(if (isKey) Kind.TYPE else Kind.STRING, text)
                i = end
                continue
            }
            if (ch.isDigit() || (ch == '.' && i + 1 < n && code[i + 1].isDigit() && (i == 0 || !code[i - 1].isLetterOrDigit()))) {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || (code[j] == '.' && j + 1 < n && code[j + 1].isDigit()))) j++
                emit(Kind.NUMBER, code.substring(i, j))
                i = j
                continue
            }
            if (spec.annotations && ch == '@' && i + 1 < n && code[i + 1].isLetter()) {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '.')) j++
                emit(Kind.ANNOTATION, code.substring(i, j))
                i = j
                continue
            }
            if (ch.isLetter() || ch == '_' || ch == '$') {
                var j = i + 1
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '$' || (spec.identKeys && code[j] == '-'))) j++
                val word = code.substring(i, j)
                val probe = if (spec.ignoreCase) word.lowercase() else word
                val kind = when {
                    probe in spec.keywords -> Kind.KEYWORD
                    spec.identKeys && nextChar(code, j) == ':' && nextChar(code, j + 1) != ':' -> Kind.FUNCTION
                    // Capitalised names are types even when followed by "(" (a declaration or constructor call).
                    word[0].isUpperCase() && word.any { it.isLowerCase() } -> Kind.TYPE
                    nextNonSpace(code, j) == '(' -> Kind.FUNCTION
                    else -> Kind.DEFAULT
                }
                if (kind == Kind.DEFAULT) plain.append(word) else emit(kind, word)
                i = j
                continue
            }
            plain.append(ch)
            i++
        }
        flush()
        return out
    }

    /** Index just past the closing delimiter, or the end of the line/text if it is unterminated. */
    private fun stringEnd(code: String, start: Int, delim: String): Int {
        val n = code.length
        var j = start + delim.length
        val multiline = delim.length > 1 || delim == "`"
        while (j < n) {
            when {
                code[j] == '\\' && delim != "`" -> j += 2
                code.startsWith(delim, j) -> return j + delim.length
                code[j] == '\n' && !multiline -> return j
                else -> j++
            }
        }
        return n
    }

    private fun nextChar(code: String, at: Int): Char? = code.getOrNull(at)

    private fun nextNonSpace(code: String, from: Int): Char? {
        var j = from
        while (j < code.length && (code[j] == ' ' || code[j] == '\t')) j++
        return code.getOrNull(j)
    }

    // ---------- XML / HTML ----------

    private fun xml(code: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        val n = code.length
        val text = StringBuilder()
        fun flush() {
            if (text.isNotEmpty()) {
                out.add(Tok(Kind.DEFAULT, text.toString()))
                text.setLength(0)
            }
        }
        while (i < n) {
            if (code.startsWith("<!--", i)) {
                flush()
                val close = code.indexOf("-->", i + 4)
                val end = if (close < 0) n else close + 3
                out.add(Tok(Kind.COMMENT, code.substring(i, end)))
                i = end
            } else if (code[i] == '<' && i + 1 < n && (code[i + 1].isLetter() || code[i + 1] == '/' || code[i + 1] == '?' || code[i + 1] == '!')) {
                flush()
                var j = i + 1
                if (code[j] == '/' || code[j] == '?' || code[j] == '!') j++
                out.add(Tok(Kind.DEFAULT, code.substring(i, j)))
                var k = j
                while (k < n && (code[k].isLetterOrDigit() || code[k] in ":-_.")) k++
                if (k > j) out.add(Tok(Kind.KEYWORD, code.substring(j, k)))
                j = k
                // attributes up to the closing '>'
                while (j < n && code[j] != '>') {
                    val c = code[j]
                    if (c == '"' || c == '\'') {
                        val close = code.indexOf(c, j + 1)
                        val end = if (close < 0) n else close + 1
                        out.add(Tok(Kind.STRING, code.substring(j, end)))
                        j = end
                    } else if (c.isLetter() || c == '_' || c == ':') {
                        var e = j + 1
                        while (e < n && (code[e].isLetterOrDigit() || code[e] in ":-_.")) e++
                        out.add(Tok(Kind.FUNCTION, code.substring(j, e)))
                        j = e
                    } else {
                        out.add(Tok(Kind.DEFAULT, c.toString()))
                        j++
                    }
                }
                if (j < n) {
                    out.add(Tok(Kind.DEFAULT, ">"))
                    j++
                }
                i = j
            } else {
                text.append(code[i])
                i++
            }
        }
        flush()
        return out
    }

    // ---------- unified diff ----------

    /** Whole-line colouring: green added, red removed, blue hunk headers, grey file headers. */
    private fun highlightDiff(code: String, c: SyntaxColors): String {
        val sb = StringBuilder()
        val lines = code.split("\n")
        lines.forEachIndexed { idx, line ->
            val esc = escape(line)
            sb.append(
                when {
                    line.startsWith("+++") || line.startsWith("---") || line.startsWith("diff ") || line.startsWith("index ") ->
                        "<font color=\"#${c.comment}\"><i>$esc</i></font>"
                    line.startsWith("@@") -> "<font color=\"#${c.keyword}\">$esc</font>"
                    line.startsWith("+") -> "<font color=\"#${c.added}\">$esc</font>"
                    line.startsWith("-") -> "<font color=\"#${c.removed}\">$esc</font>"
                    else -> esc
                },
            )
            if (idx < lines.lastIndex) sb.append('\n')
        }
        return sb.toString()
    }
}
