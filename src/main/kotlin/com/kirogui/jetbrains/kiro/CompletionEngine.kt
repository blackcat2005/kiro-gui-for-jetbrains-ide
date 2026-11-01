package com.kirogui.jetbrains.kiro

import com.kirogui.jetbrains.acp.SlashCommand

enum class SuggestionKind { COMMAND, SUBCOMMAND, SKILL, FILE }

/** [insert] replaces the typed token; it already carries any trailing space. */
data class Suggestion(val label: String, val detail: String, val insert: String, val kind: SuggestionKind)

/** The text range [start, end) to replace, and what to offer for it. */
data class Completion(val start: Int, val end: Int, val suggestions: List<Suggestion>)

data class SkillRef(val name: String, val description: String)

/** Path relative to the project, with '/' separators. */
data class FileEntry(val path: String, val recent: Boolean = false)

/**
 * Decides what to suggest for the chat input:
 *  - `/` at the very start: Kiro's slash commands and your skills; after `/cmd ` its subcommands.
 *  - `@` anywhere: skills and project files (recently opened ones first while nothing is typed yet).
 * Pure text logic; the sources are supplied lazily so the IDE side can cache them.
 */
class CompletionEngine(
    private val commands: () -> List<SlashCommand>,
    private val skills: () -> List<SkillRef>,
    private val files: () -> List<FileEntry>,
    private val limit: Int = 30,
) {

    fun complete(text: String, caret: Int): Completion? {
        if (caret < 0 || caret > text.length) return null
        return slash(text, caret) ?: mention(text, caret)
    }

    // ---------- "/" ----------

    private fun slash(text: String, caret: Int): Completion? {
        if (!text.startsWith("/")) return null
        val left = text.substring(0, caret)
        if (left.isEmpty() || '\n' in left) return null

        val sub = SUBCOMMAND.matchEntire(left)
        if (sub != null) {
            val command = commands().firstOrNull { it.name.equals("/" + sub.groupValues[1], ignoreCase = true) } ?: return null
            val typed = sub.groupValues[2]
            val items = command.subcommands.filter { it.startsWith(typed, ignoreCase = true) }
                .map { Suggestion(it, command.name, "$it ", SuggestionKind.SUBCOMMAND) }
            return items.takeIf { it.isNotEmpty() }?.let { Completion(caret - typed.length, tokenEnd(text, caret), it) }
        }
        if (left.any { it.isWhitespace() }) return null

        val query = left.drop(1)
        val all = commands().map { Suggestion(it.name, it.description, it.name + " ", SuggestionKind.COMMAND) } +
            skills().map { Suggestion("/" + it.name, "skill · " + it.description, "/" + it.name + " ", SuggestionKind.SKILL) }
        val seen = HashSet<String>()
        val ranked = all.filter { seen.add(it.label.lowercase()) }
            .mapNotNull { s -> nameScore(s.label.drop(1), query)?.let { it to s } }
            .sortedBy { it.first }.map { it.second }.take(limit)
        return ranked.takeIf { it.isNotEmpty() }?.let { Completion(0, tokenEnd(text, caret), it) }
    }

    // ---------- "@" ----------

    private fun mention(text: String, caret: Int): Completion? {
        var start = caret
        while (start > 0 && !text[start - 1].isWhitespace()) start--
        if (start >= text.length || text[start] != '@') return null
        val query = text.substring(start + 1, caret)

        val skillHits = skills().mapNotNull { s -> nameScore(s.name, query)?.let { it to s } }
            .sortedBy { it.first }.take(6)
            .map { (_, s) -> Suggestion(s.name, "skill · " + s.description, "the `${s.name}` skill ", SuggestionKind.SKILL) }
        val fileHits = FileRanker.rank(files(), query, limit).map { f ->
            val name = f.path.substringAfterLast('/')
            val ref = if (f.path.any { it.isWhitespace() }) "@`${f.path}` " else "@${f.path} "
            Suggestion(name, f.path.substringBeforeLast('/', ""), ref, SuggestionKind.FILE)
        }
        val items = (skillHits + fileHits).take(limit)
        return items.takeIf { it.isNotEmpty() }?.let { Completion(start, tokenEnd(text, caret), it) }
    }

    private fun tokenEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length && !text[i].isWhitespace()) i++
        return i
    }

    /** Lower is better; null when it doesn't match at all. */
    private fun nameScore(name: String, query: String): Int? = when {
        query.isEmpty() -> 0
        name.startsWith(query, ignoreCase = true) -> 0
        name.contains(query, ignoreCase = true) -> 1
        else -> null
    }

    private companion object {
        val SUBCOMMAND = Regex("^/(\\S+)[ \\t]+(\\S*)$")
    }
}

object FileRanker {

    /** With no query, only recently opened files are offered; otherwise names are matched before paths. */
    fun rank(files: List<FileEntry>, query: String, limit: Int): List<FileEntry> {
        if (query.isEmpty()) return files.filter { it.recent }.take(limit.coerceAtMost(12))
        val q = query.lowercase()
        return files.mapNotNull { f ->
            val name = f.path.substringAfterLast('/').lowercase()
            val score = when {
                name.startsWith(q) -> 0
                name.contains(q) -> 1
                isSubsequence(q, name) -> 2
                f.path.lowercase().contains(q) -> 3
                else -> return@mapNotNull null
            }
            Triple(score, if (f.recent) 0 else 1, f)
        }.sortedWith(compareBy({ it.first }, { it.second }, { it.third.path.length }, { it.third.path }))
            .take(limit).map { it.third }
    }

    /** "ls" matches "LoginService" (letters in order, not necessarily adjacent). */
    private fun isSubsequence(q: String, name: String): Boolean {
        var i = 0
        for (c in name) if (i < q.length && c == q[i]) i++
        return i == q.length
    }
}
