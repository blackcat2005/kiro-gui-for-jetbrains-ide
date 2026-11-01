package com.kirogui.jetbrains.kiro

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionsTest {

    @TempDir
    lateinit var home: Path

    private fun legacy(id: String, title: String?, cwd: String, parent: String? = null, reason: String = "subagent", prompt: String? = "hello there") {
        val dir = home.resolve("sessions/cli").also { Files.createDirectories(it) }
        val parentJson = if (parent != null) ",\"parent_session_id\":\"$parent\"" else ""
        val titleJson = if (title != null) "\"$title\"" else "null"
        val cwdJson = cwd.replace("\\", "\\\\")
        Files.writeString(
            dir.resolve("$id.json"),
            "{\"session_id\":\"$id\",\"cwd\":\"$cwdJson\",\"created_at\":\"2026-11-01T10:00:00Z\"," +
                "\"updated_at\":\"2026-11-01T11:00:00Z\",\"title\":$titleJson,\"session_created_reason\":\"$reason\"$parentJson," +
                "\"session_state\":{\"agent_name\":\"x\"}}",
        )
        Files.writeString(
            dir.resolve("$id.jsonl"),
            if (prompt == null) "" else "{\"version\":\"v1\",\"kind\":\"Prompt\",\"data\":{\"content\":[{\"kind\":\"text\",\"data\":\"$prompt\"}]}}\n",
        )
    }

    private fun v3(project: String, id: String, title: String, parent: String? = null, reason: String? = null, created: String = "2026-10-11T04:00:00Z") {
        val dir = home.resolve("sessions/$project/$id").also { Files.createDirectories(it) }
        val extra = (if (parent != null) ",\"parentSessionId\":\"$parent\"" else "") +
            (if (reason != null) ",\"createdReason\":\"$reason\"" else "")
        Files.writeString(
            dir.resolve("session.json"),
            "{\"id\":\"$id\",\"title\":\"$title\",\"workspacePaths\":[\"D:\\\\pose_camera\"],\"createdAt\":\"$created\"," +
                "\"lastModifiedAt\":\"$created\"$extra}",
        )
        Files.writeString(dir.resolve("messages.jsonl"), "{\"payload\":{\"type\":\"user\",\"content\":\"first question\"}}\n")
    }

    @Test
    fun `scans both stores and links tangents to parents`() {
        legacy("a1", null, "D:\\proj", prompt = "Fix the login bug please")
        legacy("a2", "Named", "D:\\proj", parent = "a1")
        legacy("empty", null, "D:\\proj", prompt = null)
        v3("h1", "sess_main", "Main chat")
        v3("h1", "sess_tan", "tangent-1", parent = "sess_main", reason = "tangent", created = "2026-10-11T05:00:00Z")

        val all = SessionScanner(home).scan().associateBy { it.id }
        assertEquals(5, all.size)
        assertEquals("Fix the login bug please", all.getValue("a1").kiroName, "untitled falls back to first prompt")
        assertEquals("Named", all.getValue("a2").title)
        assertFalse(all.getValue("empty").hasContent)
        assertTrue(all.getValue("a1").resumable)
        assertFalse(all.getValue("sess_main").resumable)
        assertEquals("tangent" to "sess_main", all.getValue("sess_tan").let { it.reason to it.parentId })
        assertEquals("D:\\pose_camera", all.getValue("sess_main").cwd)
    }

    @Test
    fun `tree nests tangents under the parent and filters`() {
        v3("h1", "sess_main", "Main chat")
        v3("h1", "sess_tan", "tangent-1", parent = "sess_main", reason = "tangent", created = "2026-10-11T05:00:00Z")
        v3("h1", "sess_deep", "tangent-2", parent = "sess_tan", reason = "tangent", created = "2026-10-11T06:00:00Z")
        legacy("a1", "Other project", "D:\\elsewhere")
        legacy("empty", "Hidden", "D:\\pose_camera", prompt = null)

        val org = SessionOrganizer(home.resolve("org.json")).also { it.load() }
        val tree = SessionTree.build(SessionScanner(home).scan(), org, SessionFilter(projectPath = "d:/pose_camera/"))
        val main = tree.single() as SessionNode.Item
        assertEquals("Main chat", main.name)
        assertEquals(listOf("tangent-1"), main.children.map { it.name })
        assertEquals(listOf("tangent-2"), main.children.single().children.map { it.name })
        assertEquals(3, SessionTree.countItems(tree))

        val withEmpty = SessionTree.build(SessionScanner(home).scan(), org, SessionFilter(projectPath = "D:\\pose_camera", hideEmpty = false))
        assertEquals(2, withEmpty.size)
    }

    @Test
    fun `folders hold roots and tangents travel with the parent`() {
        v3("h1", "sess_main", "Main chat")
        v3("h1", "sess_tan", "tangent-1", parent = "sess_main", reason = "tangent")
        v3("h1", "sess_other", "Other")
        val org = SessionOrganizer(home.resolve("org.json")).also { it.load() }
        val work = org.createFolder("Work", null)
        val sub = org.createFolder("Sprint 1", work.id)
        org.assign("sess_main", sub.id)

        val tree = SessionTree.build(SessionScanner(home).scan(), org)
        val top = tree.filterIsInstance<SessionNode.FolderNode>().single()
        assertEquals("Work", top.folder.name)
        val inner = top.children.single() as SessionNode.FolderNode
        val placed = inner.children.single() as SessionNode.Item
        assertEquals("sess_main", placed.session.id)
        assertEquals(listOf("sess_tan"), placed.children.map { it.session.id })
        assertEquals("sess_other", tree.filterIsInstance<SessionNode.Item>().single().session.id)
        assertEquals("Work / Sprint 1", org.pathOf(sub.id))
    }

    @Test
    fun `organizer persists, validates and deletes without losing sessions`() {
        val file = home.resolve("org.json")
        val org = SessionOrganizer(file).also { it.load() }
        val a = org.createFolder("A", null)
        val b = org.createFolder("B", a.id)
        assertFailsWith<IllegalArgumentException> { org.createFolder("a", null) }
        assertFailsWith<IllegalArgumentException> { org.createFolder("  ", null) }
        assertFailsWith<IllegalArgumentException> { org.moveFolder(a.id, b.id) }
        org.assign("s1", b.id)
        org.setAlias("s1", "  My name ")

        val reloaded = SessionOrganizer(file).also { it.load() }
        assertEquals(b.id, reloaded.folderOf("s1"))
        assertEquals("My name", reloaded.alias("s1"))

        reloaded.deleteFolder(b.id)
        assertEquals(a.id, reloaded.folderOf("s1"), "sessions move up to the parent")
        reloaded.deleteFolder(a.id)
        assertNull(reloaded.folderOf("s1"))
        reloaded.setAlias("s1", "")
        assertNull(reloaded.alias("s1"))
    }

    @Test
    fun `rename writes Kiro's own title and keeps the rest of the file`() {
        v3("h1", "sess_main", "Old title")
        legacy("a1", null, "D:\\proj")
        val scanner = SessionScanner(home)
        val all = scanner.scan().associateBy { it.id }

        scanner.writeTitle(all.getValue("sess_main"), "Renamed")
        scanner.writeTitle(all.getValue("a1"), "Legacy renamed")

        val after = scanner.scan().associateBy { it.id }
        assertEquals("Renamed", after.getValue("sess_main").title)
        assertEquals("Legacy renamed", after.getValue("a1").title)
        val text = Files.readString(all.getValue("sess_main").metaFile)
        assertTrue(text.contains("\"titleSetByUser\": true"))
        assertTrue(text.contains("workspacePaths"), "other fields preserved")
        assertTrue(Files.readString(all.getValue("a1").metaFile).contains("\"agent_name\":\"x\""))
        assertFalse(Files.list(all.getValue("a1").metaFile.parent).use { s -> s.anyMatch { it.toString().endsWith(".tmp") } })
    }
}
