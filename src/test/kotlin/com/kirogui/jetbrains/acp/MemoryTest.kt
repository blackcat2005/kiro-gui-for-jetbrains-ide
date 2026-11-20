package com.kirogui.jetbrains.acp

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun `a list answer becomes memories`() {
        val page = json(
            """{"memories":[
                 {"id":"m1","memoryType":"USER_PREFERENCE","scopes":["workspace:app"],"title":"Tabs","summary":"Uses tabs","content":"Always tabs","why":"team style","updatedAt":"2026-11-20T10:00:00Z"},
                 {"id":"m2","memoryType":"SEMANTIC","scopes":[],"title":"","summary":"s","content":"c","createdAt":"2026-11-01T00:00:00Z"},
                 {"title":"no id"}],
               "total":3,"counts":{"byType":{},"byScope":{}},"nextCursor":"abc"}""",
        )
        val list = MemoryParse.list(page)
        assertEquals(listOf("m1", "m2"), list.map { it.id })
        assertTrue(list[0].isPreference)
        assertEquals("team style", list[0].why)
        assertEquals(listOf("workspace:app"), list[0].scopes)
        assertEquals("2026-11-01T00:00:00Z", list[1].updatedAt) // falls back to createdAt
        assertEquals("abc", MemoryParse.nextCursor(page))
    }

    @Test
    fun `a get answer wraps the record`() {
        val m = MemoryParse.one(json("""{"memory":{"id":"m9","memoryType":"SEMANTIC","title":"T","summary":"S","content":"C"}}"""))
        assertEquals("m9", m?.id)
        assertNull(MemoryParse.one(json("""{"memory":null}""")))
        assertEquals(emptyList(), MemoryParse.list(json("""{"error":1}""")))
    }

    @Test
    fun `session meta is only sent when a mode was chosen`() {
        assertNull(MemoryParse.sessionMeta(null, true))
        val rw = MemoryParse.sessionMeta(MemoryMode.READ_WRITE, true).toString()
        assertEquals("""{"kiro":{"settings":{"memory":{"mode":"read_write","reflection":true}}}}""", rw)
        // reflection only means something with write access
        val ro = MemoryParse.sessionMeta(MemoryMode.READ_ONLY, true).toString()
        assertEquals("""{"kiro":{"settings":{"memory":{"mode":"read_only","reflection":false}}}}""", ro)
    }
}
