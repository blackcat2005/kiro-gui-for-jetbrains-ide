package com.kirogui.jetbrains.kiro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

enum class Scope(val label: String) { WORKSPACE("workspace"), GLOBAL("global") }

data class ResourceItem(val name: String, val scope: Scope, val path: Path, val summary: String)

data class SpecInfo(
    val name: String,
    val dir: Path,
    val isBugfix: Boolean,
    val requirements: Path?,
    val design: Path?,
    val tasks: Path?,
    val tasksDone: Int,
    val tasksTotal: Int,
)

data class HookInfo(
    val name: String,
    val file: Path,
    val scope: Scope,
    val trigger: String,
    val matcher: String?,
    val actionType: String,
    val enabled: Boolean,
    val description: String?,
)

/**
 * Reads and edits what Kiro keeps under `.kiro/`: skills, steering, specs and hooks.
 * Layouts follow the Kiro docs (kiro.dev/docs/skills, /steering, /specs, /hooks).
 * No IDE dependencies; every method works on plain files.
 */
class KiroResources(private val workspace: Path?, private val home: Path) {

    private val json = Json { prettyPrint = true }

    private fun root(scope: Scope): Path? = when (scope) {
        Scope.WORKSPACE -> workspace?.resolve(".kiro")
        Scope.GLOBAL -> home.resolve(".kiro")
    }

    private fun requireRoot(scope: Scope): Path =
        root(scope) ?: throw IllegalArgumentException("No project is open for a workspace-level item.")

    private val scopes = listOf(Scope.WORKSPACE, Scope.GLOBAL)

    // ---------- listing ----------

    fun skills(): List<ResourceItem> = scopes.flatMap { scope ->
        children(root(scope)?.resolve("skills")).filter { it.isDirectory() }.mapNotNull { dir ->
            val file = dir.resolve("SKILL.md")
            if (!file.exists()) null
            else ResourceItem(dir.name, scope, file, parseFrontmatter(file.readSafe())["description"].orEmpty())
        }
    }

    fun steering(): List<ResourceItem> = scopes.flatMap { scope ->
        children(root(scope)?.resolve("steering")).filter { it.extension == "md" }.map { file ->
            val inclusion = parseFrontmatter(file.readSafe())["inclusion"] ?: "always"
            ResourceItem(file.nameWithoutExtension, scope, file, "inclusion: $inclusion")
        }
    }

    fun specs(): List<SpecInfo> = children(root(Scope.WORKSPACE)?.resolve("specs")).filter { it.isDirectory() }.map { dir ->
        fun file(n: String) = dir.resolve(n).takeIf { it.exists() }
        val tasks = file("tasks.md")
        val counts = tasks?.let { countTasks(it.readSafe()) } ?: (0 to 0)
        SpecInfo(
            dir.name, dir, isBugfix = file("bugfix.md") != null,
            requirements = file("requirements.md") ?: file("bugfix.md"),
            design = file("design.md"), tasks = tasks,
            tasksDone = counts.first, tasksTotal = counts.second,
        )
    }

    fun hooks(): List<HookInfo> = scopes.flatMap { scope ->
        children(root(scope)?.resolve("hooks")).filter { it.extension == "json" }.flatMap { file ->
            val hooks = try {
                Json.parseToJsonElement(file.readSafe()).jsonObject["hooks"]?.jsonArray
            } catch (_: Exception) {
                null
            }
            hooks.orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                HookInfo(
                    name = o.str("name") ?: return@mapNotNull null,
                    file = file, scope = scope,
                    trigger = o.str("trigger") ?: "?",
                    matcher = o.str("matcher"),
                    actionType = (o["action"] as? JsonObject)?.str("type") ?: "?",
                    enabled = o["enabled"]?.jsonPrimitive?.booleanOrNull ?: true,
                    description = o.str("description"),
                )
            }
        }
    }

    // ---------- creating ----------

    fun createSkill(name: String, description: String, scope: Scope): Path {
        require(SKILL_NAME.matches(name)) { "Skill names use lowercase letters, digits and hyphens only (max 64 characters)." }
        require(description.isNotBlank()) { "A skill needs a description saying when to use it." }
        val file = requireRoot(scope).resolve("skills/$name/SKILL.md")
        return writeNew(
            file,
            "---\nname: $name\ndescription: ${description.trim().replace("\n", " ")}\n---\n\n# $name\n\nDescribe the steps Kiro should follow.\n",
        )
    }

    /** [extra] is the glob for fileMatch, or the description for auto; ignored otherwise. */
    fun createSteering(name: String, inclusion: String, extra: String?, scope: Scope): Path {
        require(FILE_NAME.matches(name)) { "Use letters, digits, dot, dash or underscore in the file name." }
        val front = when (inclusion) {
            "always" -> "inclusion: always\n"
            "manual" -> "inclusion: manual\n"
            "fileMatch" -> "inclusion: fileMatch\nfileMatchPattern: \"${extra?.takeIf { it.isNotBlank() } ?: "**/*"}\"\n"
            "auto" -> {
                require(!extra.isNullOrBlank()) { "Auto steering needs a description." }
                "inclusion: auto\nname: $name\ndescription: ${extra.trim()}\n"
            }
            else -> throw IllegalArgumentException("Unknown inclusion mode: $inclusion")
        }
        return writeNew(requireRoot(scope).resolve("steering/$name.md"), "---\n$front---\n\n# $name\n\n")
    }

    fun createSpec(name: String, bugfix: Boolean): Path {
        require(SKILL_NAME.matches(name)) { "Spec names use lowercase letters, digits and hyphens only." }
        val dir = requireRoot(Scope.WORKSPACE).resolve("specs/$name")
        val first = if (bugfix) "bugfix.md" else "requirements.md"
        val firstBody = if (bugfix) "# Bugfix: $name\n\n## Current behavior\n\n## Expected behavior\n\n## Unchanged behavior\n"
        else "# Requirements: $name\n\n## User stories\n\n## Acceptance criteria\n"
        writeNew(dir.resolve("design.md"), "# Design: $name\n\n")
        writeNew(dir.resolve("tasks.md"), "# Tasks: $name\n\n- [ ] First task\n")
        return writeNew(dir.resolve(first), firstBody)
    }

    fun createHook(name: String, trigger: String, matcher: String?, command: String, scope: Scope): Path {
        require(FILE_NAME.matches(name)) { "Use letters, digits, dot, dash or underscore in the hook name." }
        require(command.isNotBlank()) { "A hook needs a command to run." }
        val hook = buildJsonObject {
            put("name", name)
            put("trigger", trigger)
            if (!matcher.isNullOrBlank()) put("matcher", matcher)
            put("action", buildJsonObject {
                put("type", "command")
                put("command", command)
            })
            put("enabled", true)
        }
        val doc = buildJsonObject {
            put("version", "v1")
            put("hooks", JsonArray(listOf(hook)))
        }
        return writeNew(requireRoot(scope).resolve("hooks/$name.json"), json.encodeToString(JsonObject.serializer(), doc) + "\n")
    }

    // ---------- editing / deleting ----------

    fun setHookEnabled(hook: HookInfo, enabled: Boolean) = rewriteHooks(hook) { o ->
        if (o.str("name") == hook.name) JsonObject(o + ("enabled" to JsonPrimitive(enabled))) else o
    }

    fun deleteHook(hook: HookInfo) {
        val root = Json.parseToJsonElement(hook.file.readText()).jsonObject
        val remaining = root["hooks"]!!.jsonArray.filterNot { (it as? JsonObject)?.str("name") == hook.name }
        if (remaining.isEmpty()) Files.delete(hook.file)
        else hook.file.toFile().writeText(json.encodeToString(JsonObject.serializer(), JsonObject(root + ("hooks" to JsonArray(remaining)))) + "\n")
    }

    /** Deletes a skill (its whole folder), a steering file, or a spec folder, depending on [item]. */
    fun deleteSkill(item: ResourceItem) = deleteRecursively(item.path.parent)
    fun deleteSteering(item: ResourceItem) = Files.delete(item.path)
    fun deleteSpec(spec: SpecInfo) = deleteRecursively(spec.dir)

    private fun rewriteHooks(hook: HookInfo, transform: (JsonObject) -> JsonObject) {
        val root = Json.parseToJsonElement(hook.file.readText()).jsonObject
        val updated = root["hooks"]!!.jsonArray.map { (it as? JsonObject)?.let(transform) ?: it }
        hook.file.toFile().writeText(json.encodeToString(JsonObject.serializer(), JsonObject(root + ("hooks" to JsonArray(updated)))) + "\n")
    }

    // ---------- helpers ----------

    private fun writeNew(file: Path, content: String): Path {
        if (file.exists()) throw IllegalArgumentException("${file.name} already exists in ${file.parent}.")
        Files.createDirectories(file.parent)
        file.toFile().writeText(content)
        return file
    }

    private fun deleteRecursively(dir: Path) {
        if (!dir.exists()) return
        Files.walk(dir).use { stream -> stream.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }

    private fun children(dir: Path?): List<Path> {
        if (dir == null || !dir.isDirectory()) return emptyList()
        return try {
            Files.list(dir).use { s -> s.sorted().toList() }
        } catch (_: IOException) {
            emptyList()
        }
    }

    private fun Path.readSafe(): String = try {
        readText()
    } catch (_: IOException) {
        ""
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val SKILL_NAME = Regex("[a-z0-9]+(-[a-z0-9]+)*")
        private val FILE_NAME = Regex("[A-Za-z0-9._-]+")

        /** Minimal `key: value` reader for the `---` block at the top of a markdown file. */
        fun parseFrontmatter(text: String): Map<String, String> {
            val lines = text.removePrefix("﻿").lines()
            if (lines.firstOrNull()?.trim() != "---") return emptyMap()
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end < 0) return emptyMap()
            return lines.subList(1, end + 1).mapNotNull { line ->
                val i = line.indexOf(':')
                if (i <= 0 || line.first().isWhitespace()) null
                else line.substring(0, i).trim() to line.substring(i + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            }.toMap()
        }

        /** (done, total) over markdown checkbox lines (`- [ ]`, `- [x]`, `- [-]`). */
        fun countTasks(markdown: String): Pair<Int, Int> {
            val box = Regex("""^\s*[-*]\s+\[([ xX\-~])]""")
            val marks = markdown.lines().mapNotNull { box.find(it)?.groupValues?.get(1) }
            return marks.count { it == "x" || it == "X" } to marks.size
        }
    }
}
