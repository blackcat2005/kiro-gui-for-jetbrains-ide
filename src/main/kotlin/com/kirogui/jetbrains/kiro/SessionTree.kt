package com.kirogui.jetbrains.kiro

import java.time.Instant
import java.time.ZoneId

sealed interface SessionNode {
    data class FolderNode(val folder: Folder, val children: List<SessionNode>) : SessionNode

    /** A date heading ("Today", "Yesterday", ...) over sessions that are not in any folder. */
    data class Group(val label: String, val children: List<Item>) : SessionNode

    /** A session; [children] are the tangents / subagent sessions that branched from it (any depth). */
    data class Item(val session: KiroSession, val name: String, val children: List<Item>) : SessionNode
}

data class SessionFilter(
    /** Only sessions started in this directory. */
    val projectPath: String? = null,
    val hideEmpty: Boolean = true,
    val query: String = "",
    /** Sessions that stay visible even if empty (the one open in the chat, so "New chat" shows up at once). */
    val keepIds: Set<String> = emptySet(),
)

object SessionTree {

    fun build(
        all: List<KiroSession>,
        org: SessionOrganizer,
        filter: SessionFilter = SessionFilter(),
        groupByDate: Boolean = false,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<SessionNode> {
        fun nameOf(s: KiroSession) = org.alias(s.id) ?: s.kiroName

        val visible = all.filter { s ->
            (!filter.hideEmpty || s.hasContent || s.id in filter.keepIds) &&
                (filter.projectPath == null || samePath(s.cwd, filter.projectPath)) &&
                (filter.query.isBlank() || nameOf(s).contains(filter.query.trim(), ignoreCase = true))
        }
        val byId = visible.associateBy { it.id }
        val childrenOf = visible.filter { it.parentId in byId && it.parentId != it.id }.groupBy { it.parentId!! }

        fun item(s: KiroSession, seen: Set<String>): SessionNode.Item = SessionNode.Item(
            s, nameOf(s),
            childrenOf[s.id].orEmpty().filter { it.id !in seen }
                .sortedBy { it.createdAt ?: Instant.EPOCH }
                .map { item(it, seen + s.id + it.id) },
        )

        // A session is a root when it has no (visible) parent. Only roots can be placed in folders;
        // their tangents and subagents travel with them.
        val roots = visible.filter { it.parentId == null || it.parentId !in byId || it.parentId == it.id }
        val byFolder = roots.groupBy { org.folderOf(it.id) }
        val sortedItems = { list: List<KiroSession> ->
            list.sortedByDescending { it.updatedAt ?: it.createdAt ?: Instant.EPOCH }.map { item(it, setOf(it.id)) }
        }

        val folders = org.folders()
        fun folderNode(f: Folder): SessionNode.FolderNode {
            val subFolders = folders.filter { it.parentId == f.id }.sortedBy { it.name.lowercase() }.map(::folderNode)
            return SessionNode.FolderNode(f, subFolders + sortedItems(byFolder[f.id].orEmpty()))
        }

        val topFolders = folders.filter { it.parentId == null || it.parentId !in folders.map { p -> p.id }.toSet() }
            .sortedBy { it.name.lowercase() }.map(::folderNode)
        val unfiled = sortedItems(byFolder[null].orEmpty())
        val unfiledNodes: List<SessionNode> = if (!groupByDate) unfiled else {
            val buckets = unfiled.groupBy { RelativeTime.bucket(it.session.updatedAt ?: it.session.createdAt, now, zone) }
            RecencyBucket.entries.mapNotNull { b -> buckets[b]?.let { SessionNode.Group(b.label, it) } }
        }
        val nodes = topFolders + unfiledNodes

        // While searching, hide folders that have nothing to show.
        return if (filter.query.isBlank()) nodes else nodes.mapNotNull(::pruneEmpty)
    }

    fun countItems(nodes: List<SessionNode>): Int = nodes.sumOf {
        when (it) {
            is SessionNode.FolderNode -> countItems(it.children)
            is SessionNode.Item -> 1 + countItems(it.children)
            is SessionNode.Group -> countItems(it.children)
        }
    }

    private fun pruneEmpty(node: SessionNode): SessionNode? = when (node) {
        is SessionNode.Item, is SessionNode.Group -> node
        is SessionNode.FolderNode -> node.children.mapNotNull(::pruneEmpty).takeIf { it.isNotEmpty() }?.let { node.copy(children = it) }
    }

    fun samePath(a: String?, b: String?): Boolean {
        if (a == null || b == null) return false
        fun norm(p: String) = p.replace('\\', '/').trimEnd('/').lowercase()
        return norm(a) == norm(b)
    }
}
