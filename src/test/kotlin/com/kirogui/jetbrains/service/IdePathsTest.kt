package com.kirogui.jetbrains.service

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.io.File
import kotlin.test.assertEquals

class IdePathsTest {

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `a lower-case drive from kiro-cli becomes the path the IDE knows`() {
        val file = File.createTempFile("kiro-path", ".txt").apply { deleteOnExit() }
        val real = file.canonicalPath
        val sent = real[0].lowercaseChar() + real.substring(1)
        assertEquals(real, IdePaths.normalize(sent, windows = true))
        assertEquals(real, IdePaths.normalize("  $sent ", windows = true))
        assertEquals(real, IdePaths.normalize(file.toURI().toString(), windows = true))
        // Not yet on disk (Kiro creating a file): still fixed.
        val fresh = File(file.parentFile, "not-there-yet.kt").canonicalPath
        assertEquals(fresh, IdePaths.normalize(fresh[0].lowercaseChar() + fresh.substring(1), windows = true))
    }

    @Test
    fun `other systems keep the path as sent`() {
        assertEquals("/home/a/Src/B.kt", IdePaths.normalize("/home/a/Src/B.kt", windows = false))
        assertEquals("/home/a/b c.kt", IdePaths.normalize("file:///home/a/b%20c.kt", windows = false).replace('\\', '/'))
    }
}
