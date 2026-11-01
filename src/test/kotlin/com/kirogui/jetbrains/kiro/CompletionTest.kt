package com.kirogui.jetbrains.kiro

import com.kirogui.jetbrains.acp.SlashCommand
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CompletionTest {

    private val engine = CompletionEngine(
        commands = {
            listOf(
                SlashCommand("/context", "Manage context files", listOf("show", "add", "remove", "clear")),
                SlashCommand("/compact", "Compact history", emptyList()),
                SlashCommand("/clear", "Clear history", emptyList()),
                SlashCommand("/agent", "Select agent", listOf("create", "swap")),
            )
        },
        skills = { listOf(SkillRef("cdk-deploy", "Deploy CDK stacks"), SkillRef("context-tips", "Notes on context")) },
        files = {
            listOf(
                FileEntry("src/main/LoginService.kt", recent = true),
                FileEntry("src/test/LoginServiceTest.kt"),
                FileEntry("README.md", recent = true),
                FileEntry("docs/my notes.md"),
                FileEntry("build.gradle.kts"),
            )
        },
    )

    private fun labels(c: Completion?) = c?.suggestions?.map { it.label }

    @Test
    fun `slash at the start offers commands and skills, best matches first`() {
        val c = assertNotNull(engine.complete("/co", 3))
        assertEquals(0 to 3, c.start to c.end)
        assertEquals(listOf("/context", "/compact", "/context-tips"), labels(c), "prefix matches only; skill included")
        assertEquals("/context ", c.suggestions[0].insert)

        val all = assertNotNull(engine.complete("/", 1))
        assertTrue(all.suggestions.size >= 6, "empty query lists everything")
    }

    @Test
    fun `slash only works at the very start`() {
        assertNull(engine.complete("hello /co", 9))
        assertNull(engine.complete("/clear\nmore /co", 15))
        assertNull(engine.complete("/zzz", 4), "nothing matches")
    }

    @Test
    fun `subcommands after the command and a space`() {
        val c = assertNotNull(engine.complete("/context sh", 11))
        assertEquals(listOf("show"), labels(c))
        assertEquals(9 to 11, c.start to c.end)
        assertEquals("show ", c.suggestions.single().insert)
        assertEquals(listOf("show", "add", "remove", "clear"), labels(engine.complete("/context ", 9)))
        assertNull(engine.complete("/clear x", 8), "command without subcommands")
    }

    @Test
    fun `mention offers files by name, then fuzzy, then path`() {
        val c = assertNotNull(engine.complete("look at @login", 14))
        assertEquals(8 to 14, c.start to c.end)
        assertEquals(listOf("LoginService.kt", "LoginServiceTest.kt"), labels(c))
        assertEquals("@src/main/LoginService.kt ", c.suggestions[0].insert)
        assertEquals("src/main", c.suggestions[0].detail)

        assertEquals("LoginService.kt", assertNotNull(engine.complete("@ls", 3)).suggestions.first().label, "initials match")
        assertEquals("build.gradle.kts", assertNotNull(engine.complete("@gradle", 7)).suggestions.first().label)
        assertEquals("@`docs/my notes.md` ", assertNotNull(engine.complete("@notes", 6)).suggestions.single().insert)
    }

    @Test
    fun `mention with nothing typed lists recent files and skills mention by name`() {
        val empty = assertNotNull(engine.complete("@", 1))
        assertEquals(setOf("LoginService.kt", "README.md"), empty.suggestions.filter { it.kind == SuggestionKind.FILE }.map { it.label }.toSet())

        val skill = assertNotNull(engine.complete("use @cdk", 8)).suggestions.first()
        assertEquals(SuggestionKind.SKILL, skill.kind)
        assertEquals("the `cdk-deploy` skill ", skill.insert)
    }

    @Test
    fun `mention replaces the whole token under the caret`() {
        val c = assertNotNull(engine.complete("see @login now", 10))
        assertEquals(4 to 10, c.start to c.end)
        val mid = assertNotNull(engine.complete("see @lo|gin now".replace("|", ""), 7))
        assertEquals(4 to 10, mid.start to mid.end, "token end extends past the caret")
        assertNull(engine.complete("email me@login", 14), "an @ inside a word is not a mention")
    }

    // ---- relative time and grouping ----

    private val zone = ZoneId.of("UTC")
    private val now = Instant.parse("2026-11-01T12:00:00Z")

    @Test
    fun `relative time wording`() {
        fun f(iso: String) = RelativeTime.format(Instant.parse(iso), now, zone)
        assertEquals("just now", f("2026-11-01T11:59:40Z"))
        assertEquals("5 min ago", f("2026-11-01T11:55:00Z"))
        assertEquals("3 h ago", f("2026-11-01T09:00:00Z"))
        assertEquals("yesterday", f("2026-10-31T23:50:00Z"), "calendar days, not 24h")
        assertEquals("4 d ago", f("2026-10-28T10:00:00Z"))
        assertEquals("11 Oct", f("2026-10-11T10:00:00Z"))
        assertEquals("3 Dec 2025", f("2025-12-03T10:00:00Z"))
        assertEquals("", RelativeTime.format(null, now, zone))
    }

    @Test
    fun `recency buckets`() {
        fun b(iso: String) = RelativeTime.bucket(Instant.parse(iso), now, zone)
        assertEquals(RecencyBucket.TODAY, b("2026-11-01T00:05:00Z"))
        assertEquals(RecencyBucket.YESTERDAY, b("2026-10-31T10:00:00Z"))
        assertEquals(RecencyBucket.WEEK, b("2026-10-27T10:00:00Z"))
        assertEquals(RecencyBucket.MONTH, b("2026-10-11T10:00:00Z"))
        assertEquals(RecencyBucket.OLDER, b("2026-08-01T10:00:00Z"))
        assertEquals(RecencyBucket.OLDER, RelativeTime.bucket(null, now, zone))
    }

    @TempDir
    lateinit var home: Path

    @Test
    fun `unfiled sessions are grouped by date but folders are not`() {
        val dir = Files.createDirectories(home.resolve("sessions/cli"))
        fun session(id: String, updated: String) {
            Files.writeString(
                dir.resolve("$id.json"),
                "{\"session_id\":\"$id\",\"cwd\":\"D:\\\\p\",\"created_at\":\"$updated\",\"updated_at\":\"$updated\",\"title\":\"$id\"}",
            )
            Files.writeString(dir.resolve("$id.jsonl"), "{\"kind\":\"Prompt\"}\n")
        }
        session("today1", "2026-11-01T10:00:00Z")
        session("today2", "2026-11-01T11:00:00Z")
        session("old", "2026-08-01T10:00:00Z")
        session("filed", "2026-11-01T09:00:00Z")

        val org = SessionOrganizer(home.resolve("org.json")).also { it.load() }
        val folder = org.createFolder("Pinned", null)
        org.assign("filed", folder.id)

        val tree = SessionTree.build(SessionScanner(home).scan(), org, groupByDate = true, now = now, zone = zone)
        assertEquals("Pinned", (tree[0] as SessionNode.FolderNode).folder.name)
        val groups = tree.filterIsInstance<SessionNode.Group>()
        assertEquals(listOf("Today", "Older"), groups.map { it.label })
        assertEquals(listOf("today2", "today1"), groups[0].children.map { it.name }, "newest first")
        assertEquals(4, SessionTree.countItems(tree))
    }
}
