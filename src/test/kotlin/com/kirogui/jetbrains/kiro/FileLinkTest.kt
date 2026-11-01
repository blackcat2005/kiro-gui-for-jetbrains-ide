package com.kirogui.jetbrains.kiro

import com.kirogui.jetbrains.ui.MarkdownHtml
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileLinkTest {

    private val files = listOf(
        FileEntry("src/main/LoginService.kt", recent = true),
        FileEntry("src/test/LoginServiceTest.kt"),
        FileEntry("docs/README.md"),
        FileEntry("a/Util.kt"),
        FileEntry("b/Util.kt"),
        FileEntry("c/Helper.kt", recent = true),
        FileEntry("d/Helper.kt", recent = true),
    )

    private fun resolver(exists: Set<String> = emptySet()) =
        FileResolver({ files }, "D:/proj", { it in exists })

    @Test
    fun `resolves relative, partial and bare names`() {
        val r = resolver()
        assertEquals("src/main/LoginService.kt", r.resolve("src/main/LoginService.kt"))
        assertEquals("src/main/LoginService.kt", r.resolve("SRC\\main\\loginservice.kt"), "case and slashes")
        assertEquals("src/main/LoginService.kt", r.resolve("./src/main/LoginService.kt"))
        assertEquals("src/main/LoginService.kt", r.resolve("main/LoginService.kt"), "path suffix")
        assertEquals("src/main/LoginService.kt", r.resolve("LoginService.kt"), "unique bare name")
        assertEquals("docs/README.md", r.resolve("README.md"))
    }

    @Test
    fun `ambiguous or unknown names do not link`() {
        val r = resolver()
        assertNull(r.resolve("Util.kt"), "two files, none recent")
        assertNull(r.resolve("Helper.kt"), "two recent files")
        assertNull(r.resolve("e.g"))
        assertNull(r.resolve("Missing.kt"))
        assertNull(r.resolve(""))
    }

    @Test
    fun `absolute paths inside the project become relative, outside need to exist`() {
        val r = resolver(exists = setOf("C:/other/Notes.txt", "D:/proj/gen/Out.kt"))
        assertEquals("src/main/LoginService.kt", r.resolve("D:\\proj\\src\\main\\LoginService.kt"))
        assertEquals("gen/Out.kt", r.resolve("D:/proj/gen/Out.kt"), "in the project but not in the scanned list")
        assertEquals("C:/other/Notes.txt", r.resolve("C:\\other\\Notes.txt"))
        assertNull(r.resolve("C:/other/Gone.txt"))
    }

    // ---- markdown integration ----

    private val linker = resolver()

    private fun links(html: String) = Regex("href=\"(kirofile:[^\"]*)\"").findAll(html).mapNotNull { MarkdownHtml.parseFileLink(it.groupValues[1]) }.toList()

    @Test
    fun `files in text and inline code become links with their line numbers`() {
        val html = MarkdownHtml.render("Edit `LoginService.kt:42`, then see src/test/LoginServiceTest.kt#L7 and docs/README.md.", linker)
        val refs = links(html)
        assertEquals(
            listOf(
                MarkdownHtml.FileRef("src/main/LoginService.kt", 42),
                MarkdownHtml.FileRef("src/test/LoginServiceTest.kt", 7),
                MarkdownHtml.FileRef("docs/README.md", null),
            ),
            refs,
        )
        assertTrue(html.contains("<a href=\"kirofile:") && html.contains("<code>LoginService.kt:42</code></a>"), "inline code is wrapped, not replaced")
        assertTrue(html.contains("README.md</a>."), "trailing punctuation stays outside the link")
    }

    @Test
    fun `line ranges, code blocks, existing links and non-files are left alone`() {
        val html = MarkdownHtml.render(
            "See src/main/LoginService.kt:12-20 and [the docs](https://kiro.dev/README.md), i.e. e.g. v1.2.3.\n\n```\nsrc/main/LoginService.kt\n```\n",
            linker,
        )
        assertEquals(listOf(MarkdownHtml.FileRef("src/main/LoginService.kt", 12)), links(html), "only the prose mention; range start is kept")
        assertTrue(html.contains("https://kiro.dev/README.md"))
    }

    @Test
    fun `without a linker nothing is linked, and the link format round-trips`() {
        assertFalse(MarkdownHtml.render("src/main/LoginService.kt").contains("kirofile:"))
        val href = MarkdownHtml.fileLink("my dir/File Name.kt", 9)
        assertEquals(MarkdownHtml.FileRef("my dir/File Name.kt", 9), MarkdownHtml.parseFileLink(href))
        assertEquals(MarkdownHtml.FileRef("x.kt", null), MarkdownHtml.parseFileLink(MarkdownHtml.fileLink("x.kt", null)))
        assertNull(MarkdownHtml.parseFileLink("https://example.com"))
    }

    @Test
    fun `tables render and code blocks are listed in order`() {
        val md = "| a | b |\n|---|---|\n| 1 | 2 |\n\n```kotlin\nval x = 1\n```\n\n    indented\n"
        assertTrue(MarkdownHtml.render(md).contains("<table>"))
        assertEquals(listOf("val x = 1", "indented"), MarkdownHtml.codeBlocks(md))
        assertNotNull(MarkdownHtml.render("plain"))
    }
}
