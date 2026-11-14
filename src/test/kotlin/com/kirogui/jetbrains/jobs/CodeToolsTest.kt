package com.kirogui.jetbrains.jobs

import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class CodeToolsTest {
    @Test fun `name pattern is a substring unless it has wildcards`() {
        val sub = CodeTools.namePattern("userserv")
        assertTrue(sub("UserService.kt"))
        assertFalse(sub("Order.kt"))
        val glob = CodeTools.namePattern("build.gradle*")
        assertTrue(glob("build.gradle.kts"))
        assertFalse(glob("settings.gradle"))
        assertTrue(CodeTools.namePattern("*.proto")("a.PROTO"))
        assertTrue(CodeTools.namePattern("a?c")("abc"))
    }

    @Test fun `generated and vcs folders are hidden from the default listing`(@TempDir dir: File) {
        val git = File(dir, ".git").also { it.mkdir() }
        val src = File(dir, "src").also { it.mkdir() }
        val file = File(dir, "build").also { it.writeText("x") }
        assertTrue(CodeTools.ignoredDir(git))
        assertFalse(CodeTools.ignoredDir(src))
        assertFalse(CodeTools.ignoredDir(file)) // a file called build is not a folder
    }
}
