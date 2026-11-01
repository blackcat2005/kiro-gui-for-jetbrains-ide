package com.kirogui.jetbrains.ui

import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.kirogui.jetbrains.kiro.HookInfo
import com.kirogui.jetbrains.kiro.KiroResources
import com.kirogui.jetbrains.kiro.ResourceItem
import com.kirogui.jetbrains.kiro.Scope
import com.kirogui.jetbrains.kiro.SpecInfo
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.event.HierarchyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ListSelectionModel

/** "Manage" tab: skills, steering, specs and hooks under `.kiro/`, workspace and global. */
class KiroResourcesPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val resources get() = KiroResources(project.basePath?.let { Path.of(it) }, Path.of(System.getProperty("user.home")))
    private val tabs = JTabbedPane()
    private val refreshers = mutableListOf<() -> Unit>()

    init {
        tabs.addTab("Skills", skillsTab())
        tabs.addTab("Steering", steeringTab())
        tabs.addTab("Specs", specsTab())
        tabs.addTab("Hooks", hooksTab())
        add(tabs, BorderLayout.CENTER)
        // Files change outside the IDE too (Kiro itself writes them); refresh whenever the tab is shown.
        addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L && isShowing) refreshAll()
        }
    }

    private fun refreshAll() = refreshers.forEach { it() }

    // ---------- tabs ----------

    private fun skillsTab() = listTab<ResourceItem>(
        hint = "Skills are folders with a SKILL.md. Kiro activates them from their description, or with /name in chat. " +
            "Custom agents need a skill:// resource to load them.",
        load = { resources.skills() },
        label = { "${it.name}  [${it.scope.label}]  ${it.summary}" },
        onNew = {
            val name = ask("Skill name (lowercase, digits, hyphens):", "New skill") ?: return@listTab
            val desc = ask("When should Kiro use this skill?", "New skill") ?: return@listTab
            val scope = askScope() ?: return@listTab
            open(resources.createSkill(name, desc, scope))
        },
        onOpen = { open(it.path) },
        onDelete = { resources.deleteSkill(it) },
    )

    private fun steeringTab() = listTab<ResourceItem>(
        hint = "Steering files give Kiro standing context. Inclusion: always, fileMatch, manual (#name in chat) or auto.",
        load = { resources.steering() },
        label = { "${it.name}  [${it.scope.label}]  ${it.summary}" },
        onNew = {
            val name = ask("Steering file name (without .md):", "New steering file") ?: return@listTab
            val modes = arrayOf("always", "fileMatch", "manual", "auto")
            val mode = modes.getOrNull(Messages.showDialog(project, "Inclusion mode:", "New steering file", modes, 0, null)) ?: return@listTab
            val extra = when (mode) {
                "fileMatch" -> ask("File pattern (glob):", "New steering file", "**/*") ?: return@listTab
                "auto" -> ask("Description (when should Kiro load this?):", "New steering file") ?: return@listTab
                else -> ""
            }
            val scope = askScope() ?: return@listTab
            open(resources.createSteering(name, mode, extra, scope))
        },
        onOpen = { open(it.path) },
        onDelete = { resources.deleteSteering(it) },
    )

    private fun specsTab() = listTab<SpecInfo>(
        hint = "Specs live in .kiro/specs/<name>/ (requirements or bugfix, design, tasks). Task progress counts checkboxes in tasks.md.",
        load = { resources.specs() },
        label = {
            val kind = if (it.isBugfix) "bugfix" else "feature"
            val progress = if (it.tasksTotal > 0) "tasks ${it.tasksDone}/${it.tasksTotal}" else "no tasks"
            "${it.name}  ($kind)  $progress"
        },
        onNew = {
            val name = ask("Spec name (lowercase, digits, hyphens):", "New spec") ?: return@listTab
            val kinds = arrayOf("Feature", "Bugfix")
            val kind = Messages.showDialog(project, "Spec type:", "New spec", kinds, 0, null)
            if (kind >= 0) open(resources.createSpec(name, bugfix = kind == 1))
        },
        onOpen = { spec -> open(spec.tasks ?: spec.requirements ?: spec.design ?: return@listTab) },
        onDelete = { resources.deleteSpec(it) },
        extras = listOf(
            "Requirements" to { s: SpecInfo -> s.requirements?.let(::open) },
            "Design" to { s: SpecInfo -> s.design?.let(::open) },
            "Tasks" to { s: SpecInfo -> s.tasks?.let(::open) },
        ),
    )

    private fun hooksTab() = listTab<HookInfo>(
        hint = "Hooks run a command or an agent prompt on events such as PostFileSave or PreToolUse (.kiro/hooks/*.json).",
        load = { resources.hooks() },
        label = {
            val state = if (it.enabled) "on " else "off"
            val matcher = it.matcher?.let { m -> "  /$m/" } ?: ""
            "[$state] ${it.name}  [${it.scope.label}]  ${it.trigger}$matcher  → ${it.actionType}"
        },
        onNew = {
            val name = ask("Hook name:", "New hook") ?: return@listTab
            val triggers = arrayOf("PostFileSave", "PreToolUse", "PostToolUse", "Stop")
            val trigger = triggers.getOrNull(Messages.showDialog(project, "Trigger:", "New hook", triggers, 0, null)) ?: return@listTab
            val matcher = ask("Matcher regex (optional, e.g. ts|tsx files):", "New hook") ?: return@listTab
            val command = ask("Shell command to run:", "New hook") ?: return@listTab
            val scope = askScope() ?: return@listTab
            open(resources.createHook(name, trigger, matcher, command, scope))
        },
        onOpen = { open(it.file) },
        onDelete = { resources.deleteHook(it) },
        extras = listOf("Enable / disable" to { h: HookInfo -> resources.setHookEnabled(h, !h.enabled) }),
    )

    // ---------- generic list tab ----------

    private fun <T> listTab(
        hint: String,
        load: () -> List<T>,
        label: (T) -> String,
        onNew: () -> Unit,
        onOpen: (T) -> Unit,
        onDelete: (T) -> Unit,
        extras: List<Pair<String, (T) -> Unit>> = emptyList(),
    ): JComponent {
        val list = JBList<T>().apply {
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            cellRenderer = SimpleListCellRenderer.create("") { label(it) }
            emptyText.text = "Nothing here yet"
        }
        val refresh = { list.model = CollectionListModel(load()) }
        refreshers.add(refresh)

        fun guarded(action: () -> Unit) {
            try {
                action()
            } catch (e: Exception) {
                Messages.showErrorDialog(project, e.message ?: e.toString(), "Kiro")
            }
            refresh()
        }

        fun button(text: String, needsSelection: Boolean, action: () -> Unit) = JButton(text).also { b ->
            b.addActionListener { if (!needsSelection || list.selectedValue != null) guarded(action) }
        }

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            add(button("New…", false, onNew))
            add(button("Open", true) { onOpen(list.selectedValue) })
            extras.forEach { (name, fn) -> add(button(name, true) { fn(list.selectedValue) }) }
            add(button("Delete", true) {
                val item = list.selectedValue
                val ok = Messages.showYesNoDialog(project, "Delete \"${label(item)}\"?", "Kiro", Messages.getWarningIcon()) == Messages.YES
                if (ok) onDelete(item)
            })
            add(JButton("Refresh").also { it.addActionListener { refresh() } })
        }
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && list.selectedValue != null) guarded { onOpen(list.selectedValue) }
            }
        })

        refresh()
        return JPanel(BorderLayout()).apply {
            add(JBLabel("<html>$hint</html>").apply { border = JBUI.Borders.empty(6) }, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    // ---------- small helpers ----------

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
