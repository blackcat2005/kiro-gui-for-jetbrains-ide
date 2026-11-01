package com.kirogui.jetbrains.kiro

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.nio.file.Paths

data class FileChange(val path: Path, val before: String, val after: String, val isNewFile: Boolean)

/**
 * Works out what a Kiro file-write tool call is about to do, from the arguments Kiro sends in its
 * permission request (Kiro writes the file itself, so this is the only moment to review it).
 * Understands the `fs_write` commands create / strReplace / insert / append; anything else returns null.
 */
object EditPreview {

    fun compute(raw: JsonObject?, cwd: Path, readExisting: (Path) -> String?): FileChange? {
        raw ?: return null
        val command = raw.str("command") ?: return null
        val rawPath = raw.str("path") ?: return null
        val path = try {
            Paths.get(rawPath).let { if (it.isAbsolute) it else cwd.resolve(it) }
        } catch (_: InvalidPathException) {
            return null
        }
        val existing = readExisting(path)?.normalize()
        val newStr = raw.str("newStr")?.normalize()

        return when (command) {
            "create" -> {
                val content = (raw.str("content") ?: raw.str("fileText"))?.normalize() ?: return null
                FileChange(path, existing ?: "", content, isNewFile = existing == null)
            }
            "strReplace" -> {
                val before = existing ?: return null
                val old = raw.str("oldStr")?.normalize() ?: return null
                val idx = before.indexOf(old)
                if (idx < 0) return null
                FileChange(path, before, before.substring(0, idx) + (newStr ?: "") + before.substring(idx + old.length), false)
            }
            "insert" -> {
                val before = existing ?: return null
                val line = raw["insertLine"]?.jsonPrimitive?.intOrNull ?: return null
                val lines = before.split("\n").toMutableList()
                lines.add(line.coerceIn(0, lines.size), newStr ?: return null)
                FileChange(path, before, lines.joinToString("\n"), false)
            }
            "append" -> {
                val before = existing ?: return null
                val sep = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
                FileChange(path, before, before + sep + (newStr ?: return null), false)
            }
            else -> null
        }
    }

    private fun String.normalize() = replace("\r\n", "\n")

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.contentOrNull
}
