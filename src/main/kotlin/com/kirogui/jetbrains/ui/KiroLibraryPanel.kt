package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.ToggleAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.kiro.Folder
import com.kirogui.jetbrains.kiro.HookInfo
import com.kirogui.jetbrains.kiro.KiroResources
import com.kirogui.jetbrains.kiro.KiroSession
import com.kirogui.jetbrains.kiro.ResourceItem
import com.kirogui.jetbrains.kiro.Scope
import com.kirogui.jetbrains.kiro.SessionFilter
import com.kirogui.jetbrains.kiro.SessionNode
import com.kirogui.jetbrains.kiro.SessionOrganizer
import com.kirogui.jetbrains.kiro.SessionScanner
import com.kirogui.jetbrains.kiro.SessionTree
import com.kirogui.jetbrains.kiro.SpecInfo
import java.awt.BorderLayout
import java.awt.event.HierarchyEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Transferable
import javax.swing.DropMode
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JTree
import javax.swing.TransferHandler
import javax.swing.JPanel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Everything Kiro keeps, in one tree: sessions (your folders, grouped by date, tangents under their parent),
 * then skills, steering files, specs and hooks. Right-click for actions; double-click opens.
 */
class KiroLibraryPanel(
    private val project: Project,
    private val onOpenSession: (KiroSession) -> Unit,
    private val onUseSkill: (String) -> Unit,
    /** Sidebar mode: sessions only, one click opens, with a New chat button. */
    private val compact: Boolean = false,
    private val onNewChat: () -> Unit = {},
    /** Id of the session open in the chat, so it can be highlighted and kept visible. */
    private val currentSessionId: () -> String? = { null },
) : JPanel(BorderLayout()) {

    private class Snapshot(
        val sessions: List<KiroSession>,
        val skills: List<ResourceItem>,
        val steering: List<ResourceItem>,
        val specs: List<SpecInfo>,
        val hooks: List<HookInfo>,
    )

    private val kiroHome: Path = Path.of(System.getProperty("user.home"), ".kiro")
    private val scanner = SessionScanner(kiroHome)
    private val organizer = SessionOrganizer(kiroHome.resolve("kirogui-sessions.json"))
    private fun resources() = KiroResources(project.basePath?.let { Path.of(it) }, Path.of(System.getProperty("user.home")))

    private var snapshot: Snapshot? = null
    private var thisProjectOnly = true
    private var hideEmpty = true
    private var expandedKeys: Set<String>? = null // null until the first build; then what the user left open

    private val search = JBTextField().apply { emptyText.text = "Filter by name" }
    private val summary = JBLabel(" ")
    private val tree = Tree(DefaultTreeModel(DefaultMutableTreeNode())).apply {
        isRootVisible = false
        showsRootHandles = true
        rowHeight = 0 // let each row be as tall as its renderer wants (two-line rows)
        cellRenderer = LibraryTreeRenderer(current = currentSessionId)
        emptyText.text = "Nothing here yet"
    }
    private lateinit var toolbarComponent: javax.swing.JComponent

    init {
        add(if (compact) compactHeader() else header(), BorderLayout.NORTH)
        add(JBScrollPane(tree).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
        add(summary.apply { border = JBUI.Borders.empty(3, 8) }, BorderLayout.SOUTH)

        search.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = rebuild()
            override fun removeUpdate(e: DocumentEvent) = rebuild()
            override fun changedUpdate(e: DocumentEvent) = rebuild()
        })
        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.button != MouseEvent.BUTTON1) return
                if (compact) {
                    if (e.clickCount == 1) singleClick(e)
                } else if (e.clickCount == 2) {
                    activate()
                }
            }

            override fun mousePressed(e: MouseEvent) = popup(e)
            override fun mouseReleased(e: MouseEvent) = popup(e)

            private fun popup(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                tree.getClosestPathForLocation(e.x, e.y)?.let { if (tree.getPathBounds(it)?.contains(e.point) == true) tree.selectionPath = it }
                showContextMenu(e.x, e.y)
            }
        })
        tree.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> activate()
                    KeyEvent.VK_F2 -> selected()?.let { rename(it) }
                    KeyEvent.VK_DELETE -> selected()?.let { delete(it) }
                }
            }
        })
        if (compact) tree.toggleClickCount = 0 // single click expands/collapses folders itself
        installDragAndDrop()
        // Kiro writes sessions and files while you work; refresh whenever the tab comes into view.
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) reload()
        }
    }

    // ---------- header ----------

    private fun header(): JPanel {
        val group = DefaultActionGroup().apply {
            add(action("New…", AllIcons.General.Add) { showNewMenu() })
            add(toggle("This project only", AllIcons.Nodes.Module, { thisProjectOnly }) { thisProjectOnly = it; rebuild() })
            add(toggle("Hide empty sessions", AllIcons.General.Filter, { hideEmpty }) { hideEmpty = it; rebuild() })
            add(action("Expand all", AllIcons.Actions.Expandall) { setAllExpanded(true) })
            add(action("Collapse all", AllIcons.Actions.Collapseall) { setAllExpanded(false) })
            add(action("Refresh", AllIcons.Actions.Refresh) { reload() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("KiroLibrary", group, true)
        toolbar.targetComponent = this
        toolbarComponent = toolbar.component
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(2, 4))
            add(toolbarComponent, BorderLayout.WEST)
            add(search.apply { border = JBUI.Borders.empty(2, 6) }, BorderLayout.CENTER)
        }
    }

    private fun compactHeader(): JPanel {
        val group = DefaultActionGroup().apply {
            add(action("New folder", AllIcons.Actions.NewFolder) { guarded { newFolder(selectedFolderId()) } })
            add(toggle("This project only", AllIcons.Nodes.Module, { thisProjectOnly }) { thisProjectOnly = it; rebuild() })
            add(toggle("Hide empty sessions", AllIcons.General.Filter, { hideEmpty }) { hideEmpty = it; rebuild() })
            add(action("Refresh", AllIcons.Actions.Refresh) { reload() })
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("KiroSidebar", group, true)
        toolbar.targetComponent = this
        toolbarComponent = toolbar.component
        val newChat = javax.swing.JButton("New chat", AllIcons.General.Add).apply { addActionListener { onNewChat() } }
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(JBUI.Borders.customLineBottom(com.intellij.ui.JBColor.border()), JBUI.Borders.empty(4, 4, 2, 4))
            add(newChat, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(toolbarComponent, BorderLayout.WEST)
                add(search.apply { border = JBUI.Borders.empty(2, 4) }, BorderLayout.CENTER)
            }, BorderLayout.CENTER)
        }
    }

    private fun action(text: String, icon: Icon?, run: () -> Unit): AnAction = object : DumbAwareAction(text, text, icon) {
        override fun actionPerformed(e: AnActionEvent) = run()
    }

    private fun toggle(text: String, icon: Icon, get: () -> Boolean, set: (Boolean) -> Unit): AnAction =
        object : ToggleAction(text, text, icon), DumbAware {
            override fun isSelected(e: AnActionEvent) = get()
            override fun setSelected(e: AnActionEvent, state: Boolean) = set(state)
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
        }

    // ---------- data ----------

    /** Re-reads Kiro's files; call after something changed (a turn finished, a session was created). */
    fun refresh() = reload()

    private fun reload() {
        if (!compact) summary.text = "Reading…"
        ApplicationManager.getApplication().executeOnPooledThread {
            val snap = try {
                organizer.load()
                val r = resources()
                if (compact) Snapshot(scanner.scan(), emptyList(), emptyList(), emptyList(), emptyList())
                else Snapshot(scanner.scan(), r.skills(), r.steering(), r.specs(), r.hooks())
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater { summary.text = "Could not read Kiro data: ${e.message}" }
                return@executeOnPooledThread
            }
            ApplicationManager.getApplication().invokeLater {
                snapshot = snap
                rebuild()
            }
        }
    }

    private fun rebuild() {
        val snap = snapshot ?: return
        val query = search.text.trim()
        val filter = SessionFilter(if (thisProjectOnly) project.basePath else null, hideEmpty, query, setOfNotNull(currentSessionId()))
        val sessionNodes = SessionTree.build(snap.sessions, organizer, filter, groupByDate = true)

        fun matches(vararg texts: String) = query.isBlank() || texts.any { it.contains(query, ignoreCase = true) }
        val skills = snap.skills.filter { matches(it.name, it.summary) }
        val steering = snap.steering.filter { matches(it.name, it.summary) }
        val specs = snap.specs.filter { matches(it.name) }
        val hooks = snap.hooks.filter { matches(it.name, it.trigger) }

        val previous = expandedKeys ?: currentExpandedKeys()
        val firstBuild = expandedKeys == null
        val root = DefaultMutableTreeNode()
        fun section(kind: SectionKind, count: Int, children: List<DefaultMutableTreeNode>) {
            root.add(DefaultMutableTreeNode(LibNode.Section(kind, count)).also { n -> children.forEach(n::add) })
        }
        if (compact) sessionNodes.forEach { root.add(sessionTreeNode(it)) }
        else section(SectionKind.SESSIONS, SessionTree.countItems(sessionNodes), sessionNodes.map(::sessionTreeNode))
        if (!compact) section(SectionKind.SKILLS, skills.size, skills.map { DefaultMutableTreeNode(LibNode.Skill(it)) })
        if (!compact) section(SectionKind.STEERING, steering.size, steering.map { DefaultMutableTreeNode(LibNode.Steering(it)) })
        if (!compact) section(SectionKind.SPECS, specs.size, specs.map(::specTreeNode))
        if (!compact) section(SectionKind.HOOKS, hooks.size, hooks.map { DefaultMutableTreeNode(LibNode.Hook(it)) })

        tree.model = DefaultTreeModel(root)
        forEachNode(root) { n ->
            val key = keyOf(n.userObject)
            val open = when {
                query.isNotBlank() -> n.childCount > 0
                firstBuild -> n.userObject.let { it is LibNode.Section || (it is LibNode.Sess && it.node !is SessionNode.Item) }
                else -> key != null && key in previous
            }
            if (open && n.childCount > 0 && n !== root) tree.expandPath(TreePath(n.path))
        }
        expandedKeys = currentExpandedKeys()
        if (compact) {
            selectCurrent(root)
            summary.text = "${SessionTree.countItems(sessionNodes)} sessions"
        } else {
            summary.text = "${SessionTree.countItems(sessionNodes)} of ${snap.sessions.size} sessions  ·  " +
                "${skills.size} skills  ·  ${steering.size} steering  ·  ${specs.size} specs  ·  ${hooks.size} hooks"
        }
    }

    /** Highlights the session that is open in the chat (selection only; it does not open anything). */
    private fun selectCurrent(root: DefaultMutableTreeNode) {
        val id = currentSessionId() ?: return
        var found: DefaultMutableTreeNode? = null
        forEachNode(root) { n -> if (keyOf(n.userObject) == "s:$id") found = n }
        found?.let {
            val path = TreePath(it.path)
            tree.selectionPath = path
            tree.scrollPathToVisible(path)
        }
    }

    private fun selectedFolderId(): String? = ((selected() as? LibNode.Sess)?.node as? SessionNode.FolderNode)?.folder?.id

    /** Sidebar behaviour: one click opens a session (if Kiro can reload it) or expands/collapses a folder or date. */
    private fun singleClick(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = ((path.lastPathComponent as? DefaultMutableTreeNode)?.userObject as? LibNode.Sess)?.node ?: return
        when (node) {
            is SessionNode.Item -> onOpenSession(node.session)
            is SessionNode.FolderNode, is SessionNode.Group -> if (tree.isExpanded(path)) tree.collapsePath(path) else tree.expandPath(path)
        }
    }

    // ---------- drag and drop ----------

    private var dragPayload: String? = null

    /** What the user may drag: a folder, or a session that is not under another visible session. */
    private fun payloadFor(node: LibNode?): String? {
        val n = (node as? LibNode.Sess)?.node ?: return null
        return when (n) {
            is SessionNode.FolderNode -> "f:" + n.folder.id
            is SessionNode.Item -> {
                val parent = n.session.parentId
                if (parent != null && snapshot?.sessions?.any { it.id == parent } == true) null else "s:" + n.session.id
            }
            is SessionNode.Group -> null
        }
    }

    /** The folder a drop would land in; null id means "no folder". */
    private data class DropFolder(val id: String?)

    /** Dropping on a folder files into it; on a date heading, the Sessions header or empty space it un-files. */
    private fun dropTarget(support: TransferHandler.TransferSupport): DropFolder? {
        val loc = support.dropLocation as? JTree.DropLocation ?: return null
        val o = (loc.path?.lastPathComponent as? DefaultMutableTreeNode)?.userObject ?: return DropFolder(null)
        return when (o) {
            is LibNode.Sess -> when (val n = o.node) {
                is SessionNode.FolderNode -> DropFolder(n.folder.id)
                is SessionNode.Group -> DropFolder(null)
                is SessionNode.Item -> null
            }
            is LibNode.Section -> if (o.kind == SectionKind.SESSIONS) DropFolder(null) else null
            else -> null
        }
    }

    private fun installDragAndDrop() {
        tree.dragEnabled = true
        tree.dropMode = DropMode.ON
        tree.transferHandler = object : TransferHandler() {
            override fun getSourceActions(c: JComponent) = MOVE

            override fun createTransferable(c: JComponent): Transferable? {
                dragPayload = payloadFor(selected())
                return dragPayload?.let { StringSelection(it) }
            }

            override fun exportDone(source: JComponent, data: Transferable?, action: Int) {
                dragPayload = null
            }

            override fun canImport(support: TransferSupport): Boolean {
                if (!support.isDrop || !support.isDataFlavorSupported(DataFlavor.stringFlavor)) return false
                val payload = dragPayload ?: return false
                val target = dropTarget(support) ?: return false
                if (payload.startsWith("f:")) {
                    val id = payload.removePrefix("f:")
                    val blocked = organizer.descendantIds(id) + id
                    if (target.id != null && target.id in blocked) return false
                }
                return true
            }

            override fun importData(support: TransferSupport): Boolean {
                if (!canImport(support)) return false
                val payload = dragPayload ?: return false
                val target = dropTarget(support) ?: return false
                val id = payload.drop(2)
                guarded {
                    if (payload.startsWith("f:")) organizer.moveFolder(id, target.id) else organizer.assign(id, target.id)
                }
                return true
            }
        }
    }

    private fun sessionTreeNode(n: SessionNode): DefaultMutableTreeNode = DefaultMutableTreeNode(LibNode.Sess(n)).also { node ->
        when (n) {
            is SessionNode.FolderNode -> n.children.forEach { node.add(sessionTreeNode(it)) }
            is SessionNode.Group -> n.children.forEach { node.add(sessionTreeNode(it)) }
            is SessionNode.Item -> n.children.forEach { node.add(sessionTreeNode(it)) }
        }
    }

    private fun specTreeNode(spec: SpecInfo) = DefaultMutableTreeNode(LibNode.Spec(spec)).also { node ->
        spec.requirements?.let { node.add(DefaultMutableTreeNode(LibNode.SpecFile(spec, if (spec.isBugfix) "Bugfix" else "Requirements", it))) }
        spec.design?.let { node.add(DefaultMutableTreeNode(LibNode.SpecFile(spec, "Design", it))) }
        spec.tasks?.let { node.add(DefaultMutableTreeNode(LibNode.SpecFile(spec, "Tasks", it))) }
    }

    private fun keyOf(o: Any?): String? = when (o) {
        is LibNode.Section -> "sec:" + o.kind
        is LibNode.Spec -> "spec:" + o.spec.name
        is LibNode.Sess -> when (val n = o.node) {
            is SessionNode.FolderNode -> "f:" + n.folder.id
            is SessionNode.Group -> "g:" + n.label
            is SessionNode.Item -> "s:" + n.session.id
        }
        else -> null
    }

    private fun forEachNode(root: DefaultMutableTreeNode, f: (DefaultMutableTreeNode) -> Unit) {
        root.depthFirstEnumeration().asSequence().map { it as DefaultMutableTreeNode }.toList().reversed().forEach(f)
    }

    private fun currentExpandedKeys(): Set<String> {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return emptySet()
        val out = HashSet<String>()
        forEachNode(root) { n -> if (n !== root && tree.isExpanded(TreePath(n.path))) keyOf(n.userObject)?.let(out::add) }
        return out
    }

    private fun setAllExpanded(expand: Boolean) {
        val root = tree.model.root as? DefaultMutableTreeNode ?: return
        forEachNode(root) { n ->
            if (n === root || n.childCount == 0) return@forEachNode
            if (expand) tree.expandPath(TreePath(n.path)) else tree.collapsePath(TreePath(n.path))
        }
        expandedKeys = currentExpandedKeys()
    }

    private fun selected(): LibNode? = (tree.lastSelectedPathComponent as? DefaultMutableTreeNode)?.userObject as? LibNode

    // ---------- activation and menus ----------

    private fun activate() {
        when (val n = selected()) {
            is LibNode.Sess -> (n.node as? SessionNode.Item)?.let { onOpenSession(it.session) }
            is LibNode.Skill -> open(n.item.path)
            is LibNode.Steering -> open(n.item.path)
            is LibNode.SpecFile -> open(n.path)
            is LibNode.Hook -> open(n.hook.file)
            is LibNode.Spec -> (n.spec.tasks ?: n.spec.requirements ?: n.spec.design)?.let(::open)
            else -> tree.selectionPath?.let { if (tree.isExpanded(it)) tree.collapsePath(it) else tree.expandPath(it) }
        }
    }

    private fun showContextMenu(x: Int, y: Int) {
        val node = selected()
        val group = DefaultActionGroup()
        fun add(text: String, run: () -> Unit) = group.add(action(text, null, run))
        when (node) {
            is LibNode.Section -> add(newLabel(node.kind)) { guarded { create(node.kind) } }
            is LibNode.Sess -> when (val s = node.node) {
                is SessionNode.Item -> {
                    add(if (s.session.resumable) "Open in chat" else "View in chat (read-only)") { onOpenSession(s.session) }
                    add("Rename…") { rename(node) }
                    add("Move to folder…") { moveSession(s) }
                    add("Show file in file manager") { RevealFileAction.openFile(s.session.metaFile.toFile()) }
                }
                is SessionNode.FolderNode -> {
                    add("New subfolder…") { guarded { newFolder(s.folder.id) } }
                    add("Rename…") { rename(node) }
                    add("Move to folder…") { moveFolder(s.folder) }
                    add("Delete folder") { delete(node) }
                }
                is SessionNode.Group -> Unit
            }
            is LibNode.Skill -> {
                add("Use in chat") { onUseSkill(node.item.name) }
                add("Open SKILL.md") { open(node.item.path) }
                add("Delete") { delete(node) }
            }
            is LibNode.Steering -> {
                add("Open") { open(node.item.path) }
                add("Delete") { delete(node) }
            }
            is LibNode.Spec -> {
                add("Open") { activate() }
                add("Delete") { delete(node) }
            }
            is LibNode.SpecFile -> add("Open") { open(node.path) }
            is LibNode.Hook -> {
                add("Open JSON") { open(node.hook.file) }
                add(if (node.hook.enabled) "Disable" else "Enable") { guarded { resources().setHookEnabled(node.hook, !node.hook.enabled) } }
                add("Delete") { delete(node) }
            }
            null -> Unit
        }
        if (group.childrenCount == 0) return
        ActionManager.getInstance().createActionPopupMenu("KiroLibraryMenu", group).component.show(tree, x, y)
    }

    private fun newLabel(kind: SectionKind) = when (kind) {
        SectionKind.SESSIONS -> "New folder…"
        SectionKind.SKILLS -> "New skill…"
        SectionKind.STEERING -> "New steering file…"
        SectionKind.SPECS -> "New spec…"
        SectionKind.HOOKS -> "New hook…"
    }

    private fun showNewMenu() {
        val kinds = SectionKind.entries
        JBPopupFactory.getInstance().createPopupChooserBuilder(kinds)
            .setRenderer(SimpleListCellRenderer.create("") { newLabel(it) })
            .setItemChosenCallback { kind -> guarded { create(kind) } }
            .createPopup()
            .showUnderneathOf(toolbarComponent)
    }

    private fun create(kind: SectionKind) {
        when (kind) {
            SectionKind.SESSIONS -> newFolder(selectedFolderId())
            SectionKind.SKILLS -> {
                val name = ask("Skill name (lowercase, digits, hyphens):", "New skill") ?: return
                val desc = ask("When should Kiro use this skill?", "New skill") ?: return
                val scope = askScope() ?: return
                open(resources().createSkill(name, desc, scope))
            }
            SectionKind.STEERING -> {
                val name = ask("Steering file name (without .md):", "New steering file") ?: return
                val modes = arrayOf("always", "fileMatch", "manual", "auto")
                val mode = modes.getOrNull(Messages.showDialog(project, "Inclusion mode:", "New steering file", modes, 0, null)) ?: return
                val extra = when (mode) {
                    "fileMatch" -> ask("File pattern (glob):", "New steering file", "**/*") ?: return
                    "auto" -> ask("Description (when should Kiro load this?):", "New steering file") ?: return
                    else -> ""
                }
                val scope = askScope() ?: return
                open(resources().createSteering(name, mode, extra, scope))
            }
            SectionKind.SPECS -> {
                val name = ask("Spec name (lowercase, digits, hyphens):", "New spec") ?: return
                val kinds = arrayOf("Feature", "Bugfix")
                val type = Messages.showDialog(project, "Spec type:", "New spec", kinds, 0, null)
                if (type >= 0) open(resources().createSpec(name, bugfix = type == 1))
            }
            SectionKind.HOOKS -> {
                val name = ask("Hook name:", "New hook") ?: return
                val triggers = arrayOf("PostFileSave", "PreToolUse", "PostToolUse", "Stop")
                val trigger = triggers.getOrNull(Messages.showDialog(project, "Trigger:", "New hook", triggers, 0, null)) ?: return
                val matcher = ask("Matcher regex (optional):", "New hook") ?: return
                val command = ask("Shell command to run:", "New hook") ?: return
                val scope = askScope() ?: return
                open(resources().createHook(name, trigger, matcher, command, scope))
            }
        }
    }

    // ---------- session and folder operations ----------

    private fun newFolder(parentId: String?) {
        val name = ask("Folder name:", "New folder") ?: return
        organizer.createFolder(name, parentId)
    }

    private fun rename(node: LibNode) {
        val sess = (node as? LibNode.Sess)?.node ?: return
        when (sess) {
            is SessionNode.FolderNode -> {
                val name = ask("Folder name:", "Rename folder", sess.folder.name) ?: return
                guarded { organizer.renameFolder(sess.folder.id, name) }
            }
            is SessionNode.Item -> {
                val name = ask("Session name (empty restores Kiro's own title):", "Rename session", sess.name) ?: return
                guarded {
                    organizer.setAlias(sess.session.id, name)
                    // Also write Kiro's own title so the CLI shows the same name; the alias above is what survives
                    // if a running Kiro process later rewrites its file.
                    if (name.isNotBlank()) {
                        try {
                            scanner.writeTitle(sess.session, name.trim())
                        } catch (e: Exception) {
                            Messages.showWarningDialog(project, "Renamed in this plugin only — could not update Kiro's file: ${e.message}", "Kiro")
                        }
                    }
                }
            }
            is SessionNode.Group -> Unit
        }
    }

    private fun moveSession(item: SessionNode.Item) {
        val parent = item.session.parentId
        if (parent != null && snapshot?.sessions?.any { it.id == parent } == true) {
            Messages.showInfoMessage(project, "This session branched from another one and moves with it. Move the parent session instead.", "Kiro")
            return
        }
        chooseFolder(emptySet()) { target -> guarded { organizer.assign(item.session.id, target?.id) } }
    }

    private fun moveFolder(folder: Folder) {
        chooseFolder(organizer.descendantIds(folder.id) + folder.id) { target -> guarded { organizer.moveFolder(folder.id, target?.id) } }
    }

    private fun chooseFolder(blocked: Set<String>, chosen: (Folder?) -> Unit) {
        val targets = listOf<Folder?>(null) + organizer.folders().filter { it.id !in blocked }.sortedBy { organizer.pathOf(it.id).lowercase() }
        fun label(f: Folder?) = f?.let { organizer.pathOf(it.id) } ?: "(no folder)"
        JBPopupFactory.getInstance().createPopupChooserBuilder(targets)
            .setTitle("Move to")
            .setRenderer(SimpleListCellRenderer.create("") { label(it) })
            .setNamerForFiltering { label(it) }
            .setItemChosenCallback { chosen(it) }
            .createPopup()
            .showInCenterOf(tree)
    }

    private fun delete(node: LibNode) {
        val what = when (node) {
            is LibNode.Sess -> (node.node as? SessionNode.FolderNode)?.let {
                "folder \"${it.folder.name}\". Its sessions and sub-folders move up one level; no session is deleted" to
                    { organizer.deleteFolder(it.folder.id) }
            }
            is LibNode.Skill -> "skill \"${node.item.name}\" (its whole folder)" to { resources().deleteSkill(node.item) }
            is LibNode.Steering -> "steering file \"${node.item.name}\"" to { resources().deleteSteering(node.item) }
            is LibNode.Spec -> "spec \"${node.spec.name}\" (its whole folder)" to { resources().deleteSpec(node.spec) }
            is LibNode.Hook -> "hook \"${node.hook.name}\"" to { resources().deleteHook(node.hook) }
            else -> null
        } ?: return
        if (Messages.showYesNoDialog(project, "Delete ${what.first}?", "Kiro", Messages.getWarningIcon()) == Messages.YES) {
            guarded(what.second)
        }
    }

    // ---------- helpers ----------

    private fun guarded(run: () -> Unit) {
        try {
            run()
        } catch (e: Exception) {
            Messages.showErrorDialog(project, e.message ?: e.toString(), "Kiro")
        }
        reload()
    }

    private fun ask(message: String, title: String, initial: String = ""): String? =
        Messages.showInputDialog(project, message, title, Messages.getQuestionIcon(), initial, null)?.trim()

    private fun askScope(): Scope? {
        if (project.basePath == null) return Scope.GLOBAL
        val options = arrayOf("Workspace (.kiro in project)", "Global (~/.kiro)")
        return when (Messages.showDialog(project, "Where should it live?", "Scope", options, 0, null)) {
            0 -> Scope.WORKSPACE
            1 -> Scope.GLOBAL
            else -> null
        }
    }

    private fun open(path: Path) {
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        FileEditorManager.getInstance(project).openFile(vf, true)
    }

}
