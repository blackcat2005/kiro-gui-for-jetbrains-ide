package com.kirogui.jetbrains.kiro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID

data class Folder(val id: String, val name: String, val parentId: String?)

/**
 * The plugin's own organisation layer, kept in one file next to Kiro's data (Kiro has no notion of folders):
 * folders (nestable), which top-level session sits in which folder, and display names.
 * Kiro's session files are never moved or restructured.
 */
class SessionOrganizer(private val file: Path) {

    private val folders = LinkedHashMap<String, Folder>()
    private val assignments = LinkedHashMap<String, String>()
    private val aliases = LinkedHashMap<String, String>()

    @Synchronized
    fun load() {
        folders.clear(); assignments.clear(); aliases.clear()
        if (!Files.exists(file)) return
        val root = try {
            Json.parseToJsonElement(Files.readString(file)).jsonObject
        } catch (_: Exception) {
            return // unreadable file: start empty rather than crash; it is rewritten on the next change
        }
        (root["folders"] as? JsonArray)?.forEach {
            val o = it.jsonObject
            val id = o.str("id") ?: return@forEach
            folders[id] = Folder(id, o.str("name") ?: "Folder", o.str("parentId"))
        }
        (root["assignments"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { assignments[k] = it } }
        (root["aliases"] as? JsonObject)?.forEach { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { aliases[k] = it } }
    }

    @Synchronized
    fun folders(): List<Folder> = folders.values.toList()

    @Synchronized
    fun folder(id: String): Folder? = folders[id]

    @Synchronized
    fun folderOf(sessionId: String): String? = assignments[sessionId]?.takeIf { it in folders }

    @Synchronized
    fun alias(sessionId: String): String? = aliases[sessionId]

    @Synchronized
    fun createFolder(name: String, parentId: String?): Folder {
        val clean = validName(name, parentId, exceptId = null)
        require(parentId == null || parentId in folders) { "Parent folder no longer exists." }
        val f = Folder(UUID.randomUUID().toString(), clean, parentId)
        folders[f.id] = f
        save()
        return f
    }

    @Synchronized
    fun renameFolder(id: String, name: String) {
        val f = folders[id] ?: return
        folders[id] = f.copy(name = validName(name, f.parentId, exceptId = id))
        save()
    }

    @Synchronized
    fun moveFolder(id: String, newParentId: String?) {
        val f = folders[id] ?: return
        require(newParentId == null || newParentId in folders) { "Target folder no longer exists." }
        require(newParentId != id && (newParentId == null || newParentId !in descendantIds(id))) { "A folder cannot be moved into itself." }
        validName(f.name, newParentId, exceptId = id)
        folders[id] = f.copy(parentId = newParentId)
        save()
    }

    /** Sub-folders and sessions of a deleted folder move up to its parent; nothing is lost. */
    @Synchronized
    fun deleteFolder(id: String) {
        val f = folders.remove(id) ?: return
        folders.replaceAll { _, child -> if (child.parentId == id) child.copy(parentId = f.parentId) else child }
        val target = f.parentId
        assignments.entries.toList().forEach { (session, folder) ->
            if (folder == id) if (target == null) assignments.remove(session) else assignments[session] = target
        }
        save()
    }

    @Synchronized
    fun assign(sessionId: String, folderId: String?) {
        require(folderId == null || folderId in folders) { "Folder no longer exists." }
        if (folderId == null) assignments.remove(sessionId) else assignments[sessionId] = folderId
        save()
    }

    /** Blank name clears the alias (falls back to Kiro's own title). */
    @Synchronized
    fun setAlias(sessionId: String, name: String?) {
        if (name.isNullOrBlank()) aliases.remove(sessionId) else aliases[sessionId] = name.trim()
        save()
    }

    @Synchronized
    fun descendantIds(folderId: String): Set<String> {
        val out = LinkedHashSet<String>()
        var frontier = listOf(folderId)
        while (frontier.isNotEmpty()) {
            frontier = folders.values.filter { it.parentId in frontier && out.add(it.id) }.map { it.id }
        }
        return out
    }

    @Synchronized
    fun pathOf(folderId: String): String {
        val parts = ArrayList<String>()
        var cur = folders[folderId]
        var guard = 0
        while (cur != null && guard++ < 64) {
            parts.add(0, cur.name)
            cur = cur.parentId?.let { folders[it] }
        }
        return parts.joinToString(" / ")
    }

    private fun validName(name: String, parentId: String?, exceptId: String?): String {
        val clean = name.trim()
        require(clean.isNotEmpty()) { "A folder needs a name." }
        require(folders.values.none { it.id != exceptId && it.parentId == parentId && it.name.equals(clean, ignoreCase = true) }) {
            "A folder named \"$clean\" already exists here."
        }
        return clean
    }

    private fun save() {
        val doc = buildJsonObject {
            put("version", 1)
            put("folders", JsonArray(folders.values.map {
                buildJsonObject {
                    put("id", it.id)
                    put("name", it.name)
                    if (it.parentId != null) put("parentId", it.parentId)
                }
            }))
            put("assignments", JsonObject(assignments.mapValues { JsonPrimitive(it.value) }))
            put("aliases", JsonObject(aliases.mapValues { JsonPrimitive(it.value) }))
        }
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.writeString(tmp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), doc) + "\n")
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: IOException) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull }
}
