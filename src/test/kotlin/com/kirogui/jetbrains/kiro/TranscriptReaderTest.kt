package com.kirogui.jetbrains.kiro

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranscriptReaderTest {

    @TempDir
    lateinit var dir: Path

    private fun session(store: SessionStore, meta: Path) =
        KiroSession("id1", null, null, null, Instant.EPOCH, Instant.EPOCH, null, null, store, meta, true)

    @Test
    fun `legacy transcript keeps prompts, assistant text and tool uses, skips thinking and results`() {
        val meta = dir.resolve("id1.json")
        Files.writeString(meta, "{}")
        Files.writeString(
            dir.resolve("id1.jsonl"),
            listOf(
                """{"kind":"Prompt","data":{"content":[{"kind":"text","data":"Why is login slow?"}]}}""",
                """{"kind":"AssistantMessage","data":{"content":[{"kind":"thinking","data":{"text":"hmm"}},{"kind":"text","data":"Let me look."},{"kind":"toolUse","data":{"name":"read","input":{"__tool_use_purpose":"Read LoginService.kt"}}}]}}""",
                """{"kind":"ToolResults","data":{"content":[{"kind":"toolResult","data":{"content":[{"kind":"text","data":"file body"}]}}]}}""",
                """{"kind":"AssistantMessage","data":{"content":[{"kind":"text","data":"It does a blocking call."}]}}""",
                "not json at all",
                """{"kind":"Prompt","data":{"content":[{"kind":"text","data":"Fix it"}]}}""",
            ).joinToString("\n") + "\n",
        )

        val items = TranscriptReader.read(session(SessionStore.LEGACY, meta))
        assertEquals(
            listOf(
                PreviewItem.User("Why is login slow?"),
                PreviewItem.Assistant("Let me look."),
                PreviewItem.Tool("Read LoginService.kt"),
                PreviewItem.Assistant("It does a blocking call."),
                PreviewItem.User("Fix it"),
            ),
            items,
        )
    }

    @Test
    fun `v3 transcript shows Say messages only and merges consecutive ones`() {
        val sessionDir = Files.createDirectories(dir.resolve("sess_x"))
        val meta = sessionDir.resolve("session.json")
        Files.writeString(meta, "{}")
        Files.writeString(
            sessionDir.resolve("messages.jsonl"),
            listOf(
                """{"payload":{"type":"turn_start"}}""",
                """{"payload":{"type":"user","content":"explain tangents"}}""",
                """{"payload":{"type":"assistant","operationType":"Reasoning","content":"..."}}""",
                """{"payload":{"type":"assistant","operationType":"Say","content":"First part."}}""",
                """{"payload":{"type":"tool_call","title":"Reading notes.md","toolName":"read"}}""",
                """{"payload":{"type":"assistant","operationType":"Say","content":"Second part."}}""",
                """{"payload":{"type":"usage_summary"}}""",
            ).joinToString("\n") + "\n",
        )

        val items = TranscriptReader.read(session(SessionStore.V3, meta))
        assertEquals(
            listOf(
                PreviewItem.User("explain tangents"),
                PreviewItem.Assistant("First part."),
                PreviewItem.Tool("Reading notes.md"),
                PreviewItem.Assistant("Second part."),
            ),
            items,
        )
    }

    @Test
    fun `missing file gives an empty preview and long sessions keep the tail`() {
        val meta = dir.resolve("id1.json")
        Files.writeString(meta, "{}")
        assertTrue(TranscriptReader.read(session(SessionStore.LEGACY, meta)).isEmpty())

        val lines = (1..50).joinToString("\n") { """{"kind":"Prompt","data":{"content":[{"kind":"text","data":"q$it"}]}}""" }
        Files.writeString(dir.resolve("id1.jsonl"), lines + "\n")
        val tail = TranscriptReader.read(session(SessionStore.LEGACY, meta), maxItems = 5)
        assertEquals(listOf("q46", "q47", "q48", "q49", "q50"), tail.map { (it as PreviewItem.User).text })
    }
}
