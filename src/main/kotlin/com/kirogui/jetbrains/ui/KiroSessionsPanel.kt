package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.kiro.Folder
import com.kirogui.jetbrains.kiro.KiroSession
import com.kirogui.jetbrains.kiro.SessionFilter
import com.kirogui.jetbrains.kiro.SessionNode
import com.kirogui.jetbrains.kiro.SessionOrganizer
import com.kirogui.jetbrains.kiro.SessionScanner
import com.kirogui.jetbrains.kiro.SessionTree
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Kiro's stored sessions as a tree: your own folders, then sessions, then the tangents and subagent
 * sessions that branched from each one. Rename, move into folders, and reopen (where Kiro allows it).
 */
class KiroSessionsPanel(
    private val project: Project,
    private val onOpen: (KiroSession) -> Unit,
) : JPanel(BorderLayout()) {

    private val kiroHome: Path = Path.of(System.getProperty("user.home"), ".kiro")
    private val scanner = SessionScanner(kiroHome)
    private val organizer = SessionOrganizer(kiroHome.resolve("kirogui-sessions.json"))

    private val thisProject = JBCheckBox("This project only", true)
    private val hideEmpty = JBCheckBox("Hide empty", true)
    private val search = JBTextField(14).apply { emptyText.text = "Search" }
    private val summary = JBLabel(" ")
    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
        isRootVisible = false
        showsRootHandles = true
        cellRenderer = object : ColoredTreeCellRenderer() {
            override fun customizeCellRenderer(tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean) {
                when (val n = (value as? DefaultMutableTreeNode)?.userObject) {
                    is SessionNode.FolderNode -> {
                        icon = AllIcons.Nodes.Folder
                        append(n.folder.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  ${SessionTree.countItems(n.children)}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    is SessionNode.Item -> {
                        val s = n.session
                        icon = if (s.parentId != null) AllIcons.Vcs.Branch else AllIcons.Toolwindows.ToolWindowMessages
                        append(n.name, if (s.resumable) SimpleTextAttributes.REGULAR_ATTRIBUTES else SimpleTextAttributes.GRAY_ATTRIBUTES)
                        val badge = listOfNotNull(s.reason, s.updatedAt?.let { DATE.format(it) }).joinToString(" · ")
                        if (badge.isNotEmpty()) append("   $badge", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
        }
    }

    private var allSessions: List<KiroSession> = emptyList()

    init {
        val top = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            add(thisProject)
            add(hideEmpty)
            add(search)
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(button("Open") { openSelected() })
            add(button("New folder…") { newFolder() })
            add(button("Rename…") { renameSelected() })
            add(button("Move to…") { moveSelected() })
            add(button("Delete folder") { deleteFolder() })
            add(JButton("Refresh").also { it.addActionListener { reload() } })
        }
        add(JPanel(BorderLayout()).apply {
            add(top, BorderLayout.NORTH)
            add(JBLabel("<html>Folders are kept by this plugin (<code>~/.kiro/kirogui-sessions.json</code>). " +
                "Tangents and subagent sessions stay with the session they branched from.</html>").apply { border = JBUI.Borders.empty(2, 8) }, BorderLayout.SOUTH)
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree), BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.CENTER)
            add(summary.apply { border = JBUI.Borders.empty(0, 8, 4, 8) }, BorderLayout.SOUTH)
        }, BorderLayout.SOUTH)

        thisProject.addActionListener { rebuild() }
        hideEmpty.addActionListener { rebuild() }
        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) (selected() as? SessionNode.Item)?.let { openSelected() }
            }
        })
        // Kiro writes sessions while you work; pick them up whenever the tab comes into view.
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) reload()
        }
    }

    // ---------- data ----------

    private fun reload() {
        summary.text = "Reading sessions…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val sessions = try {
                organizer.load()
                scanner.scan()
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater { summary.text = "Could not read sessions: ${e.message}" }
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                allSessions = sessions
                rebuild()
            }
        }
    }

    private fun rebuild() {
        val filter = SessionFilter(
            projectPath = if (thisProject.isSelected) project.basePath else null,
            hideEmpty = hideEmpty.isSelected,
            query = search.text,
        )
        val nodes = SessionTree.build(allSessions, organizer, filter)

        val expandedKeys = expandedKeys()
        val root = DefaultMutableTreeNode()
        nodes.forEach { root.add(toTreeNode(it)) }
        tree.model = DefaultTreeModel(root)
        if (expandedKeys.isEmpty() && search.text.isBlank()) {
            // first load: open folders only, keep the (possibly long) session list tidy
            for (i in 0 until root.childCount) (root.getChildAt(i) as DefaultMutableTreeNode).let { if (it.userObject is SessionNode.FolderNode) tree.expandPath(javax.swing.tree.TreePath(it.path)) }
        } else if (search.text.isNotBlank()) {
            var row = 0
            while (row < tree.rowCount) tree.expandRow(row++)
        } else {
            restoreExpanded(root, expandedKeys)
        }
        summary.text = "${SessionTree.countItems(nodes)} session(s) shown of ${allSessions.size}"
    }

    private fun toTreeNode(n: SessionNode): DefaultMutableTreeNode = DefaultMutableTreeNode(n).also { node ->
        when (n) {
            is SessionNode.FolderNode -> n.children.forEach { node.add(toTreeNode(it)) }
            is SessionNode.Item -> n.children.forEach { node.add(toTreeNode(it)) }
        }
    }

    private fun key(n: Any?): String? = when (n) {
        is SessionNode.FolderNode -> "f:" + n.folder.id
        is SessionNode.Item -> "s:" + n.session.id
        else -> null
    }

    private fun expandedKeys(): Set<String> {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return emptySet()
        val out = HashSet<String>()
        root.depthFirstEnumeration().asSequence().map { it as DefaultMutableTreeNode }.forEach { n ->
            if (n !== root && tree.isExpanded(javax.swing.tree.TreePath(n.path))) key(n.userObject)?.let(out::add)
        }
        return out
    }

    private fun restoreExpanded(root: DefaultMutableTreeNode, keys: Set<String>) {
        root.depthFirstEnumeration().asSequence().map { it as DefaultMutableTreeNode }.toList().reversed().forEach { n ->
            if (key(n.userObject) in keys) tree.expandPath(javax.swing.tree.TreePath(n.path))
        }
    }

    private fun selected(): SessionNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? SessionNode

    // ---------- actions ----------

    private fun openSelected() {
        val s = (selected() as? SessionNode.Item)?.session ?: return
        if (s.resumable) onOpen(s)
        else Messages.showInfoMessage(
            project,
            "This session is stored in Kiro's newer format (${s.metaFile.parent.fileName}). The ACP server of the installed kiro-cli " +
                "can't reload it, so it can be organised and renamed here but not reopened in this chat.",
            "Kiro",
        )
    }

    private fun newFolder() {
        val parent = (selected() as? SessionNode.FolderNode)?.folder?.id
        val name = Messages.showInputDialog(project, "Folder name:", "New folder", Messages.getQuestionIcon())?.trim() ?: return
        guarded { organizer.createFolder(name, parent) }
    }

    private fun renameSelected() {
        when (val n = selected()) {
            is SessionNode.FolderNode -> {
                val name = Messages.showInputDialog(project, "Folder name:", "Rename folder", Messages.getQuestionIcon(), n.folder.name, null) ?: return
                guarded { organizer.renameFolder(n.folder.id, name) }
            }
            is SessionNode.Item -> {
                val name = Messages.showInputDialog(
                    project, "Session name (empty restores Kiro's own title):", "Rename session",
                    Messages.getQuestionIcon(), n.name, null,
                ) ?: return
                guarded {
                    organizer.setAlias(n.session.id, name)
                    // Also write Kiro's own title so the CLI shows the same name; the alias above is what survives
                    // if a running Kiro process later rewrites its file.
                    if (name.isNotBlank()) {
                        try {
                            scanner.writeTitle(n.session, name.trim())
                        } catch (e: Exception) {
                            Messages.showWarningDialog(project, "Renamed in this plugin only — could not update Kiro's file: ${e.message}", "Kiro")
                        }
                    }
                }
            }
            null -> Unit
        }
    }

    private fun moveSelected() {
        val n = selected() ?: return
        if (n is SessionNode.Item && n.session.parentId != null && allSessions.any { it.id == n.session.parentId }) {
            Messages.showInfoMessage(project, "This session branched from another one and moves with it. Move the parent session instead.", "Kiro")
            return
        }
        val blocked: Set<String> = if (n is SessionNode.FolderNode) organizer.descendantIds(n.folder.id) + n.folder.id else emptySet()
        val targets = listOf<Folder?>(null) + organizer.folders().filter { it.id !in blocked }.sortedBy { organizer.pathOf(it.id).lowercase() }
        JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
            .setTitle("Move to")
            .setRenderer(com.intellij.ui.SimpleListCellRenderer.create("") { f -> f?.let { organizer.pathOf(it.id) } ?: "(no folder)" })
            .setNamerForFiltering { f -> f?.let { organizer.pathOf(it.id) } ?: "(no folder)" }
            .setItemChosenCallback { target ->
                guarded {
                    when (n) {
                        is SessionNode.FolderNode -> organizer.moveFolder(n.folder.id, target?.id)
                        is SessionNode.Item -> organizer.assign(n.session.id, target?.id)
                    }
                }
            }
            .createPopup()
            .showInCenterOf(tree)
    }

    private fun deleteFolder() {
        val f = (selected() as? SessionNode.FolderNode)?.folder ?: return
        val ok = Messages.showYesNoDialog(
            project, "Delete folder \"${f.name}\"? Its sessions and sub-folders move up one level; no session is deleted.",
            "Kiro", Messages.getWarningIcon(),
        ) == Messages.YES
        if (ok) guarded { organizer.deleteFolder(f.id) }
    }

    private fun guarded(action: () -> Unit) {
        try {
            action()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Kiro")
        }
        reload()
    }

    private fun button(text: String, action: () -> Unit) = JButton(text).also { it.addActionListener { action() } }

    companion object {
        private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault())
    }
}
