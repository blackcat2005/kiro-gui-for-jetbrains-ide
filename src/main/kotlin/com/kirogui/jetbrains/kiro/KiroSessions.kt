package com.kirogui.jetbrains.kiro

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Kiro keeps sessions in two places:
 *  - LEGACY: `~/.kiro/sessions/cli/<uuid>.json` (+ `.jsonl` events) — what `kiro-cli acp` creates and can reload;
 *  - V3:     `~/.kiro/sessions/<projectHash>/sess_<uuid>/session.json` (+ `messages.jsonl`) — the newer store,
 *            which is where tangents live (`createdReason: "tangent"` + `parentSessionId`).
 */
enum class SessionStore { LEGACY, V3 }

data class KiroSession(
    val id: String,
    val title: String?,
    val firstPrompt: String?,
    val cwd: String?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    val parentId: String?,
    /** "tangent", "subagent", ... as recorded by Kiro. */
    val reason: String?,
    val store: SessionStore,
    val metaFile: Path,
    val hasContent: Boolean,
) {
    /** Only the legacy store can be reloaded through `session/load` by the ACP server. */
    val resumable: Boolean get() = store == SessionStore.LEGACY

    /** Kiro's own name for it: its title, else the first thing the user said. */
    val kiroName: String
        get() = title?.takeIf { it.isNotBlank() && it != "New Session" }
            ?: firstPrompt?.take(70)
            ?: "(empty session)"
}

class SessionScanner(private val kiroHome: Path) {

    private val sessionsDir: Path get() = kiroHome.resolve("sessions")

    fun scan(): List<KiroSession> = legacy() + v3()

    private fun legacy(): List<KiroSession> {
        val dir = sessionsDir.resolve("cli")
        return list(dir).filter { it.name.endsWith(".json") }.mapNotNull { file ->
            val o = readObject(file) ?: return@mapNotNull null
            val id = o.str("session_id") ?: return@mapNotNull null
            val events = dir.resolve("$id.jsonl")
            val title = o.str("title")
            KiroSession(
                id = id, title = title,
                firstPrompt = if (title.isNullOrBlank()) firstLegacyPrompt(events) else null,
                cwd = o.str("cwd"),
                createdAt = o.str("created_at")?.toInstant(), updatedAt = o.str("updated_at")?.toInstant(),
                parentId = o.str("parent_session_id"), reason = o.str("session_created_reason"),
                store = SessionStore.LEGACY, metaFile = file, hasContent = size(events) > 0,
            )
        }
    }

    private fun v3(): List<KiroSession> = list(sessionsDir)
        .filter { it.isDirectory() && it.name != "cli" && !it.name.startsWith(".") }
        .flatMap { projectDir -> list(projectDir).filter { it.isDirectory() && it.name.startsWith("sess_") } }
        .mapNotNull { dir ->
            val file = dir.resolve("session.json")
            val o = readObject(file) ?: return@mapNotNull null
            val id = o.str("id") ?: return@mapNotNull null
            val messages = dir.resolve("messages.jsonl")
            val title = o.str("title")
            val workspace = (o["workspacePaths"] as? JsonArray ?: o["rootPaths"] as? JsonArray)
                ?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            KiroSession(
                id = id, title = title,
                firstPrompt = if (title.isNullOrBlank() || title == "New Session") firstV3Prompt(messages) else null,
                cwd = workspace,
                createdAt = o.str("createdAt")?.toInstant(), updatedAt = o.str("lastModifiedAt")?.toInstant(),
                parentId = o.str("parentSessionId"), reason = o.str("createdReason"),
                store = SessionStore.V3, metaFile = file, hasContent = size(messages) > 0,
            )
        }

    /**
     * Writes [title] into Kiro's own session file so the CLI shows it too. Best effort: a running Kiro process
     * may rewrite the file later, which is why the plugin also keeps its own alias ([SessionOrganizer]).
     */
    fun writeTitle(session: KiroSession, title: String) {
        val original = readObject(session.metaFile) ?: throw IOException("Cannot read ${session.metaFile}")
        val updated = HashMap<String, JsonElement>(original).also { m ->
            m["title"] = JsonPrimitive(title)
            if (session.store == SessionStore.V3) m["titleSetByUser"] = JsonPrimitive(true)
        }
        // Keep the original key order.
        val ordered = LinkedHashMap<String, JsonElement>()
        original.keys.forEach { ordered[it] = updated.getValue(it) }
        updated.forEach { (k, v) -> ordered.putIfAbsent(k, v) }

        val json = if (session.store == SessionStore.V3) Json { prettyPrint = true; prettyPrintIndent = "  " } else Json
        val text = json.encodeToString(JsonObject.serializer(), JsonObject(ordered))
        val tmp = session.metaFile.resolveSibling(session.metaFile.name + ".kirogui.tmp")
        Files.writeString(tmp, text)
        try {
            Files.move(tmp, session.metaFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            Files.move(tmp, session.metaFile, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    // ---------- file helpers ----------

    private fun firstLegacyPrompt(events: Path): String? = firstLines(events, 5).firstNotNullOfOrNull { line ->
        val o = parse(line) ?: return@firstNotNullOfOrNull null
        if (o.str("kind") != "Prompt") return@firstNotNullOfOrNull null
        val content = (o["data"] as? JsonObject)?.get("content") as? JsonArray
        content?.firstNotNullOfOrNull { (it as? JsonObject)?.takeIf { c -> c.str("kind") == "text" }?.str("data") }?.oneLine()
    }

    private fun firstV3Prompt(messages: Path): String? = firstLines(messages, 20).firstNotNullOfOrNull { line ->
        val payload = parse(line)?.get("payload") as? JsonObject ?: return@firstNotNullOfOrNull null
        if (payload.str("type") != "user") null else payload.str("content")?.oneLine()
    }

    private fun firstLines(file: Path, max: Int): List<String> = try {
        if (!file.exists()) emptyList() else Files.newBufferedReader(file).use { r -> generateSequence { r.readLine() }.take(max).toList() }
    } catch (_: Exception) {
        emptyList()
    }

    private fun parse(text: String): JsonObject? = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (_: Exception) {
        null
    }

    private fun readObject(file: Path): JsonObject? = try {
        parse(Files.readString(file))
    } catch (_: Exception) {
        null
    }

    private fun list(dir: Path): List<Path> = try {
        if (dir.isDirectory()) Files.list(dir).use { s -> s.sorted().toList() } else emptyList()
    } catch (_: IOException) {
        emptyList()
    }

    private fun size(file: Path): Long = try {
        if (file.exists()) Files.size(file) else 0
    } catch (_: IOException) {
        0
    }

    private fun String.toInstant(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

    private fun String.oneLine(): String = trim().replace(Regex("\\s+"), " ")

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.let { (it as? JsonPrimitive)?.contentOrNull }
}
