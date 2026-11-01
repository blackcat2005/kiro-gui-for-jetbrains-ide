package com.kirogui.jetbrains.kiro

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KiroCoreTest {

    @TempDir
    lateinit var tmp: Path

    private fun res(): KiroResources {
        val ws = tmp.resolve("ws").also { Files.createDirectories(it) }
        val home = tmp.resolve("home").also { Files.createDirectories(it) }
        return KiroResources(ws, home)
    }

    @Test
    fun `skills round trip with frontmatter and scope`() {
        val r = res()
        r.createSkill("cdk-deploy", "Deploy CDK stacks. Use when running cdk deploy.", Scope.WORKSPACE)
        r.createSkill("notes", "Personal notes", Scope.GLOBAL)
        val skills = r.skills()
        assertEquals(listOf("cdk-deploy" to Scope.WORKSPACE, "notes" to Scope.GLOBAL), skills.map { it.name to it.scope })
        assertEquals("Deploy CDK stacks. Use when running cdk deploy.", skills[0].summary)
        assertFailsWith<IllegalArgumentException> { r.createSkill("Bad Name", "x", Scope.WORKSPACE) }
        assertFailsWith<IllegalArgumentException> { r.createSkill("cdk-deploy", "dup", Scope.WORKSPACE) }
        r.deleteSkill(skills[0])
        assertEquals(listOf("notes"), r.skills().map { it.name })
    }

    @Test
    fun `steering inclusion modes`() {
        val r = res()
        r.createSteering("style", "always", null, Scope.WORKSPACE)
        r.createSteering("ts", "fileMatch", "**/*.ts", Scope.WORKSPACE)
        r.createSteering("api", "auto", "REST conventions", Scope.GLOBAL)
        assertFailsWith<IllegalArgumentException> { r.createSteering("x", "auto", " ", Scope.GLOBAL) }
        val byName = r.steering().associateBy { it.name }
        assertEquals("inclusion: always", byName["style"]!!.summary)
        assertEquals("inclusion: fileMatch", byName["ts"]!!.summary)
        assertTrue(byName["ts"]!!.path.toFile().readText().contains("fileMatchPattern: \"**/*.ts\""))
        assertEquals(Scope.GLOBAL, byName["api"]!!.scope)
    }

    @Test
    fun `specs count task checkboxes`() {
        val r = res()
        r.createSpec("login", bugfix = false)
        val spec = r.specs().single()
        assertEquals("login", spec.name)
        assertEquals(0 to 1, spec.tasksDone to spec.tasksTotal)
        spec.tasks!!.toFile().writeText("- [x] a\n  - [x] a1\n- [ ] b\n- [-] c\nnot a task\n")
        val again = r.specs().single()
        assertEquals(2 to 4, again.tasksDone to again.tasksTotal)
        assertEquals(Pair(1, 2), KiroResources.countTasks("- [X] a\n* [ ] b"))
    }

    @Test
    fun `hooks list toggle and delete`() {
        val r = res()
        val file = r.createHook("lint", "PostFileSave", "\\.ts$", "npx eslint --fix", Scope.WORKSPACE)
        var hook = r.hooks().single()
        assertEquals(Triple("lint", "PostFileSave", "command"), Triple(hook.name, hook.trigger, hook.actionType))
        assertTrue(hook.enabled)
        r.setHookEnabled(hook, false)
        hook = r.hooks().single()
        assertFalse(hook.enabled)
        assertTrue(file.toFile().readText().contains("\"matcher\""), "other fields preserved")
        r.deleteHook(hook)
        assertTrue(r.hooks().isEmpty())
        assertFalse(Files.exists(file), "file removed when its last hook goes")
    }

    @Test
    fun `workspace items need an open project`() {
        val r = KiroResources(null, tmp)
        assertFailsWith<IllegalArgumentException> { r.createSkill("a", "b", Scope.WORKSPACE) }
        assertTrue(r.specs().isEmpty())
    }

    // ---- edit preview ----

    private val cwd: Path get() = tmp

    @Test
    fun `create and strReplace produce before and after`() {
        val file = tmp.resolve("a.txt")
        file.toFile().writeText("hi\r\nthere")
        val read = { p: Path -> if (Files.exists(p)) p.toFile().readText() else null }

        val create = EditPreview.compute(buildJsonObject { put("command", "create"); put("path", "new.txt"); put("content", "x") }, cwd, read)!!
        assertTrue(create.isNewFile)
        assertEquals("" to "x", create.before to create.after)

        val replace = EditPreview.compute(
            buildJsonObject { put("command", "strReplace"); put("path", "a.txt"); put("oldStr", "hi"); put("newStr", "hello world") }, cwd, read,
        )!!
        assertEquals("hi\nthere" to "hello world\nthere", replace.before to replace.after)

        val missing = EditPreview.compute(
            buildJsonObject { put("command", "strReplace"); put("path", "a.txt"); put("oldStr", "nope"); put("newStr", "x") }, cwd, read,
        )
        assertNull(missing)
    }

    @Test
    fun `insert append and unknown commands`() {
        tmp.resolve("b.txt").toFile().writeText("l1\nl2")
        val read = { p: Path -> if (Files.exists(p)) p.toFile().readText() else null }
        val ins = EditPreview.compute(buildJsonObject { put("command", "insert"); put("path", "b.txt"); put("insertLine", 1); put("newStr", "mid") }, cwd, read)
        assertEquals("l1\nmid\nl2", assertNotNull(ins).after)
        val app = EditPreview.compute(buildJsonObject { put("command", "append"); put("path", "b.txt"); put("newStr", "end") }, cwd, read)
        assertEquals("l1\nl2\nend", assertNotNull(app).after)
        assertNull(EditPreview.compute(buildJsonObject { put("command", "ls"); put("path", "b.txt") }, cwd, read))
        assertNull(EditPreview.compute(null, cwd, read))
    }
}
