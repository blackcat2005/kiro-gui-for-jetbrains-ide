package com.kirogui.jetbrains.ui

import com.kirogui.jetbrains.kiro.Folder
import com.kirogui.jetbrains.kiro.HookInfo
import com.kirogui.jetbrains.kiro.KiroSession
import com.kirogui.jetbrains.kiro.ResourceItem
import com.kirogui.jetbrains.kiro.Scope
import com.kirogui.jetbrains.kiro.SessionNode
import com.kirogui.jetbrains.kiro.SessionStore
import com.kirogui.jetbrains.kiro.SpecInfo
import org.junit.jupiter.api.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Path
import java.time.Instant
import javax.imageio.ImageIO
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import kotlin.test.assertTrue

/** Paints the real Library renderer with sample data to a PNG, so the row design can be looked at without an IDE. */
class LibraryRenderTest {

    private val now = Instant.parse("2026-11-01T12:00:00Z")

    private fun session(id: String, title: String, cwd: String, minutesAgo: Long, parent: String? = null, reason: String? = null, v3: Boolean = false) =
        SessionNode.Item(
            KiroSession(
                id, title, null, cwd, now.minusSeconds(minutesAgo * 60), now.minusSeconds(minutesAgo * 60), parent, reason,
                if (v3) SessionStore.V3 else SessionStore.LEGACY, Path.of("x"), true,
            ),
            title, emptyList(),
        )

    private fun node(o: Any, vararg kids: DefaultMutableTreeNode) = DefaultMutableTreeNode(o).also { n -> kids.forEach(n::add) }
    private fun sess(n: SessionNode, vararg kids: DefaultMutableTreeNode) = node(LibNode.Sess(n), *kids)

    private fun buildTree(): JTree {
        val tangent = session("t", "pricing", "D:\\pose_camera", 90, parent = "m", reason = "tangent", v3 = true)
        val main = session("m", "Document project architecture", "D:\\pose_camera", 120, v3 = true)
        val fix = session("f", "Fix the login bug on CI and make the clock injectable in LoginService", "D:\\pose_camera", 25)
        val old = session("o", "Which QuerySet methods implement filter()?", "D:\\django", 60 * 24 * 12)
        val sub = session("s", "Explore the migration autodetector", "D:\\django", 60 * 24 * 12 + 5, parent = "o", reason = "subagent")

        val spec = SpecInfo("login-flow", Path.of("s"), false, Path.of("r"), Path.of("d"), Path.of("t"), 3, 8)
        val root = node(
            "root",
            node(
                LibNode.Section(SectionKind.SESSIONS, 5),
                sess(SessionNode.FolderNode(Folder("1", "Work", null), emptyList()), sess(SessionNode.Item(main.session, main.name, emptyList()), sess(tangent))),
                sess(SessionNode.Group("Today", emptyList()), sess(fix)),
                sess(SessionNode.Group("Previous 30 days", emptyList()), sess(old, sess(sub))),
            ),
            node(
                LibNode.Section(SectionKind.SKILLS, 2),
                node(LibNode.Skill(ResourceItem("cdk-deploy", Scope.WORKSPACE, Path.of("p"), "Deploy AWS CDK stacks with best practices. Use when running cdk deploy."))),
                node(LibNode.Skill(ResourceItem("notes", Scope.GLOBAL, Path.of("p"), ""))),
            ),
            node(LibNode.Section(SectionKind.STEERING, 1), node(LibNode.Steering(ResourceItem("style", Scope.WORKSPACE, Path.of("p"), "inclusion: always")))),
            node(
                LibNode.Section(SectionKind.SPECS, 1),
                node(LibNode.Spec(spec), node(LibNode.SpecFile(spec, "Requirements", Path.of("r"))), node(LibNode.SpecFile(spec, "Tasks", Path.of("t")))),
            ),
            node(
                LibNode.Section(SectionKind.HOOKS, 2),
                node(LibNode.Hook(HookInfo("lint", Path.of("h"), Scope.WORKSPACE, "PostFileSave", "\\.(ts|tsx)$", "command", true, null))),
                node(LibNode.Hook(HookInfo("guard", Path.of("h"), Scope.GLOBAL, "PreToolUse", null, "agent", false, null))),
            ),
        )
        return JTree(DefaultTreeModel(root)).apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = 0
            cellRenderer = LibraryTreeRenderer(now = { now })
            var row = 0
            while (row < rowCount) expandRow(row++)
            setSelectionRow(2)
        }
    }

    private fun layoutAll(c: java.awt.Component) {
        if (c is java.awt.Container) {
            c.doLayout()
            c.components.forEach(::layoutAll)
        }
    }

    private fun paint(tree: JTree, bg: Color, name: String): Int {
        tree.background = bg
        val w = 460
        tree.setSize(w, 10)
        val h = tree.preferredSize.height + 8
        tree.setSize(w, h)
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = bg
        g.fillRect(0, 0, w, h)
        // An off-screen tree has no native peer, and Swing only lays out components that have one — so lay out
        // and paint each row's renderer by hand (this is what a displayed tree does for us).
        for (row in 0 until tree.rowCount) {
            val b = tree.getRowBounds(row) ?: continue
            val c = tree.cellRenderer.getTreeCellRendererComponent(
                tree, tree.getPathForRow(row).lastPathComponent, tree.isRowSelected(row), tree.isExpanded(row),
                tree.model.isLeaf(tree.getPathForRow(row).lastPathComponent), row, false,
            )
            c.setBounds(0, 0, w, b.height)
            layoutAll(c)
            val gg = g.create(0, b.y, w, b.height)
            c.paint(gg)
            gg.dispose()
        }
        g.dispose()
        // Dark glyph pixels (tree guide lines are light), so a blank render is caught by the test, not by eye.
        var dark = 0
        for (x in 0 until w) for (y in 0 until h) {
            val rgb = img.getRGB(x, y)
            if ((rgb shr 16 and 0xFF) < 110 && (rgb shr 8 and 0xFF) < 110 && (rgb and 0xFF) < 110) dark++
        }
        System.getProperty("transcript.out")?.let { dir -> File(dir, name).also { it.parentFile.mkdirs() }.let { ImageIO.write(img, "png", it) } }
        return dark
    }

    @Test
    fun `renders the library tree`() {
        val tree = buildTree()
        val dark = paint(tree, Color.WHITE, "library-light.png")
        assertTrue(dark > 800, "rows must contain visible text, found only $dark dark pixels")
        assertTrue(tree.rowCount > 15, "all sections expanded")
        assertTrue(TreePath(arrayOf(tree.model.root)).pathCount == 1)
    }
}
