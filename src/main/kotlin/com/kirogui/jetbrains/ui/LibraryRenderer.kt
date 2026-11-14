package com.kirogui.jetbrains.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.kirogui.jetbrains.kiro.AgentInfo
import com.kirogui.jetbrains.kiro.HookInfo
import com.kirogui.jetbrains.kiro.McpInfo
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
    SESSIONS("Sessions", KiroIcons.Session),
    SKILLS("Skills", AllIcons.Nodes.Plugin),
    STEERING("Steering", AllIcons.Actions.Edit),
    SPECS("Specs", AllIcons.Nodes.Package),
    HOOKS("Hooks", AllIcons.Actions.Execute),
    AGENTS("Agents", AllIcons.General.User),
    MCP("MCP servers", AllIcons.Nodes.Plugin),
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
    data class Agent(val agent: AgentInfo, val active: Boolean) : LibNode
    data class Mcp(val server: McpInfo) : LibNode
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
    /** The list is already limited to this project, so its name on every row is noise. */
    var showProject: Boolean = true


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
        room = (tree.visibleRect.width - (((tree.getPathForRow(row)?.pathCount ?: 1) - 1) * JBUI.scale(20)) - JBUI.scale(52)).coerceAtLeast(JBUI.scale(80))
        fontOf = tree.font

        val bg = if (selected) UIUtil.getTreeSelectionBackground(tree.hasFocus()) else tree.background ?: UIUtil.getTreeBackground()
        val fg = if (selected) UIUtil.getTreeSelectionForeground(tree.hasFocus()) else UIUtil.getTreeForeground()
        val quiet = if (selected) fg else UIUtil.getContextHelpForeground()
        val accent = if (selected) fg else KiroChatPanel.ACCENT
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
                // An empty section recedes.
                title.append(node.kind.title, if (node.count == 0) small else bold)
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
            is LibNode.Agent -> {
                iconLabel.icon = AllIcons.General.User
                val a = node.agent
                if (node.active) title.append("● ", badge)
                title.append(a.name, if (node.active) bold else plain)
                title.append("  ${a.scope.label}", small)
                if (node.active) title.append("  in use", badge)
                val mcp = when {
                    a.ownMcp.isNotEmpty() -> "MCP: " + a.ownMcp.joinToString(", ") + if (a.includeMcpJson) " + mcp.json" else ""
                    a.includeMcpJson -> "MCP: mcp.json"
                    else -> "no MCP"
                }
                val line = listOfNotNull(a.model, if (a.toolCount > 0) "${a.toolCount} tools" else null, mcp)
                detailLine(line.joinToString("  ·  ").ifBlank { a.description }, small)
            }
            is LibNode.Mcp -> {
                iconLabel.icon = AllIcons.Nodes.Plugin
                val m = node.server
                title.append(m.name, if (m.enabled) plain else attrs(quiet, SimpleTextAttributes.STYLE_STRIKEOUT))
                title.append(if (m.enabled) "  on" else "  off", badge)
                title.append("  ${m.scope.label}  ${m.transport}", small)
                detailLine(listOfNotNull(m.offReason, m.summary.takeIf { it.isNotBlank() }).joinToString("  ·  "), small)
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
                iconLabel.icon = if (s.parentId != null) KiroIcons.SessionChild else KiroIcons.Session
                if (s.id == current()) {
                    title.append("● ", badge)
                    title.append(fit(n.name, bold), bold)
                } else {
                    title.append(fit(n.name, plain), plain)
                }

                val project = s.cwd?.replace('\\', '/')?.trimEnd('/')?.substringAfterLast('/').orEmpty()
                val parts = listOf(if (showProject) project else "", RelativeTime.format(s.updatedAt ?: s.createdAt, now())).filter { it.isNotEmpty() }
                detail.append(parts.joinToString("  ·  "), small)
                s.reason?.takeIf { it.isNotBlank() }?.let { detail.append("   $it", small) }
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

    /** Pixels left for a title in the current row, and the font it is drawn in. */
    private var room = Int.MAX_VALUE
    private var fontOf: java.awt.Font? = null

    /** [text] cut with an ellipsis so it fits the row instead of being clipped mid-letter. */
    private fun fit(text: String, attrs: SimpleTextAttributes): String {
        val base = fontOf ?: return truncate(text, 80)
        val font = base.deriveFont(if (attrs.style and SimpleTextAttributes.STYLE_BOLD != 0) java.awt.Font.BOLD else java.awt.Font.PLAIN)
        val metrics = title.getFontMetrics(font)
        if (metrics.stringWidth(text) <= room) return text
        var end = text.length
        while (end > 1 && metrics.stringWidth(text.substring(0, end) + "…") > room) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private fun truncate(s: String, max: Int) = if (s.length <= max) s else s.take(max - 1) + "…"
}
