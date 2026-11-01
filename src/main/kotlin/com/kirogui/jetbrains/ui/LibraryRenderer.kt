package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kirogui.jetbrains.kiro.HookInfo
import com.kirogui.jetbrains.kiro.RelativeTime
import com.kirogui.jetbrains.kiro.ResourceItem
import com.kirogui.jetbrains.kiro.SessionNode
import com.kirogui.jetbrains.kiro.SpecInfo
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.nio.file.Path
import java.time.Instant
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.TreeCellRenderer

enum class SectionKind(val title: String, val icon: Icon) {
    SESSIONS("Sessions", AllIcons.Toolwindows.ToolWindowMessages),
    SKILLS("Skills", AllIcons.Nodes.Plugin),
    STEERING("Steering", AllIcons.Actions.Edit),
    SPECS("Specs", AllIcons.Nodes.Package),
    HOOKS("Hooks", AllIcons.Actions.Execute),
}

/** What a row in the Library tree stands for. */
sealed interface LibNode {
    data class Section(val kind: SectionKind, val count: Int) : LibNode
    data class Sess(val node: SessionNode) : LibNode
    data class Skill(val item: ResourceItem) : LibNode
    data class Steering(val item: ResourceItem) : LibNode
    data class Spec(val spec: SpecInfo) : LibNode
    data class SpecFile(val spec: SpecInfo, val label: String, val path: Path) : LibNode
    data class Hook(val hook: HookInfo) : LibNode
}

/**
 * Two-line rows (title, then a quieter detail line) with small badges, in the style of the IDE's own lists.
 * Sections and date headings are single-line headers.
 */
class LibraryTreeRenderer(
    private val now: () -> Instant = { Instant.now() },
    /** Id of the session open in the chat, shown in bold with a dot. */
    private val current: () -> String? = { null },
) : TreeCellRenderer {

    private val rowBorder = JBUI.Borders.empty(3, 6)
    private val sectionBorder = JBUI.Borders.empty(10, 6, 3, 6) // extra air above each section header
    private val row = JPanel(BorderLayout(JBUI.scale(8), 0)).apply { border = rowBorder }
    private val iconLabel = JBLabel().apply { verticalAlignment = JBLabel.TOP }
    private val title = SimpleColoredComponent().apply { isOpaque = false }
    private val detail = SimpleColoredComponent().apply { isOpaque = false }
    private val lines = JPanel().apply {
        isOpaque = false
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }

    init {
        // The structure never changes between rows (only text and visibility do), so layout stays valid.
        title.alignmentX = Component.LEFT_ALIGNMENT
        detail.alignmentX = Component.LEFT_ALIGNMENT
        lines.add(title)
        lines.add(detail)
        row.add(iconLabel, BorderLayout.WEST)
        row.add(lines, BorderLayout.CENTER)
    }

    override fun getTreeCellRendererComponent(
        tree: JTree, value: Any?, selected: Boolean, expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean,
    ): Component {
        val node = (value as? DefaultMutableTreeNode)?.userObject
        title.clear(); detail.clear(); detail.isVisible = false; iconLabel.icon = null

        val bg = if (selected) UIUtil.getTreeSelectionBackground(tree.hasFocus()) else tree.background ?: UIUtil.getTreeBackground()
        val fg = if (selected) UIUtil.getTreeSelectionForeground(tree.hasFocus()) else UIUtil.getTreeForeground()
        val quiet = if (selected) fg else UIUtil.getContextHelpForeground()
        val accent = if (selected) fg else JBUI.CurrentTheme.Link.Foreground.ENABLED
        this.row.background = bg
        this.row.isOpaque = true
        this.row.border = if (node is LibNode.Section) sectionBorder else rowBorder

        val plain = attrs(fg)
        val bold = attrs(fg, SimpleTextAttributes.STYLE_BOLD)
        val small = attrs(quiet, SimpleTextAttributes.STYLE_SMALLER)
        val badge = attrs(accent, SimpleTextAttributes.STYLE_SMALLER or SimpleTextAttributes.STYLE_BOLD)

        when (node) {
            is LibNode.Section -> {
                iconLabel.icon = node.kind.icon
                title.append(node.kind.title, bold)
                title.append("  ${node.count}", small)
            }
            is LibNode.Sess -> session(node.node, plain, bold, small, badge)
            is LibNode.Skill -> {
                iconLabel.icon = AllIcons.Nodes.Plugin
                title.append(node.item.name, plain)
                title.append("  ${node.item.scope.label}", small)
                detailLine(node.item.summary, small)
            }
            is LibNode.Steering -> {
                iconLabel.icon = AllIcons.FileTypes.Text
                title.append(node.item.name, plain)
                title.append("  ${node.item.scope.label}", small)
                detailLine(node.item.summary, small)
            }
            is LibNode.Spec -> {
                iconLabel.icon = AllIcons.Nodes.Package
                val s = node.spec
                title.append(s.name, plain)
                title.append(if (s.isBugfix) "  bugfix" else "  feature", small)
                val progress = if (s.tasksTotal > 0) "tasks ${s.tasksDone}/${s.tasksTotal}" else "no tasks yet"
                detailLine(progress, small)
            }
            is LibNode.SpecFile -> {
                iconLabel.icon = AllIcons.FileTypes.Text
                title.append(node.label, plain)
            }
            is LibNode.Hook -> {
                iconLabel.icon = AllIcons.Actions.Execute
                val h = node.hook
                title.append(h.name, if (h.enabled) plain else attrs(quiet, SimpleTextAttributes.STYLE_STRIKEOUT))
                if (!h.enabled) title.append("  off", badge)
                title.append("  ${h.scope.label}", small)
                detailLine(listOfNotNull(h.trigger, h.matcher?.let { "/$it/" }, "→ ${h.actionType}").joinToString("  "), small)
            }
            else -> title.append(node?.toString().orEmpty(), plain)
        }
        return this.row
    }

    private fun session(n: SessionNode, plain: SimpleTextAttributes, bold: SimpleTextAttributes, small: SimpleTextAttributes, badge: SimpleTextAttributes) {
        when (n) {
            is SessionNode.FolderNode -> {
                iconLabel.icon = AllIcons.Nodes.Folder
                title.append(n.folder.name, bold)
                title.append("  ${com.kirogui.jetbrains.kiro.SessionTree.countItems(n.children)}", small)
            }
            is SessionNode.Group -> {
                title.append(n.label.uppercase(), small)
            }
            is SessionNode.Item -> {
                val s = n.session
                iconLabel.icon = if (s.parentId != null) AllIcons.Vcs.Branch else AllIcons.Toolwindows.ToolWindowMessages
                if (s.id == current()) {
                    title.append("● ", badge)
                    title.append(truncate(n.name, 80), bold)
                } else {
                    title.append(truncate(n.name, 80), plain)
                }

                val project = s.cwd?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/').orEmpty()
                val parts = listOf(project, RelativeTime.format(s.updatedAt ?: s.createdAt, now())).filter { it.isNotEmpty() }
                detail.append(parts.joinToString("  ·  "), small)
                s.reason?.takeIf { it.isNotBlank() }?.let { detail.append("   $it", badge) }
                if (!s.resumable) detail.append("   view only", small)
                detail.isVisible = true
            }
        }
    }

    private fun detailLine(text: String, small: SimpleTextAttributes) {
        if (text.isNotBlank()) {
            detail.append(truncate(text, 90), small)
            detail.isVisible = true
        }
    }

    private fun attrs(color: Color, style: Int = SimpleTextAttributes.STYLE_PLAIN) = SimpleTextAttributes(style, color)

    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
