package com.kirogui.jetbrains.jobs

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JobsCoreTest {

    @Test
    fun `newer notice with the same key replaces the older one, obsolete ones are dropped`() {
        val q = WakeQueue()
        q.post(Notice("s1", "debug-1", "paused at A", "A"))
        q.post(Notice("s1", "job-2", "job 2 done", "B"))
        q.post(Notice("s1", "debug-1", "paused at C", "C"))
        q.post(Notice("s2", "job-3", "other chat", "D"))
        var resumed = false
        q.post(Notice("s1", "debug-9", "paused at E", "E", obsolete = { resumed }))
        resumed = true

        assertTrue(q.has("s1"))
        assertEquals(listOf("B", "C"), q.take("s1").map { it.text })
        assertFalse(q.has("s1"))
        assertEquals(emptyList(), q.take("s1"))
        assertEquals(listOf("D"), q.take("s2").map { it.text })
    }

    @Test
    fun `wake prompt tags each notice and says it is not from the user`() {
        val text = WakePrompt.compose(listOf(Notice("s", "a", "x", "Job 1 ended."), Notice("s", "b", "y", "Job 2 ended.")))
        assertTrue(text.startsWith("[kiro-gui] Job 1 ended.\n\n[kiro-gui] Job 2 ended."))
        assertTrue(text.contains("not typed by the user"))
    }

    @Test
    fun `output buffer keeps the tail and collapses progress lines`() {
        val out = OutputBuffer(limit = 50)
        out.append("downloading 10%\rdownloading 100%\r\n")
        assertEquals("downloading 100%", out.tail(5))
        repeat(20) { out.append("line $it\n") }
        val tail = out.tail(3)
        assertEquals("…\nline 17\nline 18\nline 19", tail)
        assertTrue(out.totalChars > 50)
        assertEquals("", OutputBuffer().tail(10))
    }

    @Test
    fun `job texts`() {
        val job = Job(4, JobKind.COMMAND, "unit tests", "gradlew test", "s", startedAt = 0)
        job.output.append("41 passed\nFAILED test_login\n")
        job.end(JobState.FAILED, 3, now = 72_000)
        assertEquals("Background job #4 (unit tests) failed, exit code 3", JobText.endedSummary(job))
        val ended = JobText.ended(job, now = 72_000)
        assertTrue(ended.startsWith("Background job #4 failed, exit code 3 after 1m 12s.\ncommand: gradlew test\n"))
        assertTrue(ended.endsWith("41 passed\nFAILED test_login"))
        assertEquals("#4 [failed] unit tests — failed, exit code 3, 1m 12s", JobText.line(job, now = 72_000))

        val debug = Job(5, JobKind.DEBUG, "App", "App", "s", startedAt = 0)
        debug.stoppedByUser.set(true)
        debug.end(JobState.STOPPED, 1, now = 5_000)
        assertEquals("Debug job #5 (App) stopped by the user", JobText.endedSummary(debug))
        assertEquals("Debug job #5: stopped by the user after 5s.\nconfiguration: App", JobText.header(debug, now = 5_000))
        assertTrue(JobText.ended(debug, now = 5_000).endsWith("configuration: App\noutput: (none)"))
        assertTrue(ended.contains("output, lines 1-2 of 2:\n"))
        assertEquals("2h 3m", JobText.duration((2 * 3600 + 3 * 60 + 9) * 1000L))
    }

    @Test
    fun `powershell script is encoded and passes the exit code on`() {
        val argv = ShellCommand.build("npm test", windows = true)
        assertEquals("powershell.exe", argv.first())
        val script = String(Base64.getDecoder().decode(argv.last()), Charsets.UTF_16LE)
        assertTrue(script.contains("\nnpm test\n"))
        assertTrue(script.contains("exit \$LASTEXITCODE"))
        assertEquals(listOf("-c", "npm test"), ShellCommand.build("npm test", windows = false).drop(1))
    }

    @Test
    fun `a chosen shell gets the command as one argument`() {
        val gitBash = { "C:\\Program Files\\Git\\bin\\bash.exe" }
        assertEquals(
            listOf("C:\\Program Files\\Git\\bin\\bash.exe", "-c", "echo \"a b\" && ls | wc -l"),
            ShellCommand.build("echo \"a b\" && ls | wc -l", windows = true, template = "git-bash -c {cmd}", gitBash = gitBash),
        )
        assertEquals(listOf("cmd", "/c", "dir /b"), ShellCommand.build("dir /b", windows = true, template = "cmd /c {cmd}"))
        assertEquals(listOf("bash", "-lc", "make"), ShellCommand.build("make", windows = false, template = "bash -lc"))
        assertEquals("Git Bash", ShellCommand.shellName(true, "git-bash -c {cmd}"))
        assertEquals("cmd", ShellCommand.shellName(true, "cmd /c {cmd}"))
        assertEquals("Windows PowerShell 5.1", ShellCommand.shellName(true, ""))
        val missing = kotlin.runCatching { ShellCommand.build("ls", windows = true, template = "git-bash -c {cmd}", gitBash = { null }) }
        assertTrue(missing.exceptionOrNull()?.message?.contains("Git Bash was not found") == true)
    }

    @Test
    fun `git bash is found in the usual folders`() {
        val root = java.nio.file.Files.createTempDirectory("gitroot").toFile()
        try {
            java.io.File(root, "Git/bin").mkdirs()
            java.io.File(root, "Git/bin/bash.exe").writeText("")
            assertEquals(java.io.File(root, "Git/bin/bash.exe").path, ShellCommand.findGitBash(mapOf("ProgramFiles" to root.path)))
            // Only git.exe on the PATH (a portable install).
            java.io.File(root, "portable/cmd").mkdirs()
            java.io.File(root, "portable/cmd/git.exe").writeText("")
            java.io.File(root, "portable/usr/bin").mkdirs()
            java.io.File(root, "portable/usr/bin/bash.exe").writeText("")
            assertEquals(
                java.io.File(root, "portable/usr/bin/bash.exe").path,
                ShellCommand.findGitBash(mapOf("PATH" to java.io.File(root, "portable/cmd").path)),
            )
            assertEquals(null, ShellCommand.findGitBash(mapOf("ProgramFiles" to java.io.File(root, "nothing").path)))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    fun `powershell really returns the native exit code and utf-8 text`() {
        fun run(command: String): Pair<Int, String> {
            val p = ProcessBuilder(ShellCommand.build(command, windows = true)).redirectErrorStream(true).start()
            val text = p.inputStream.readBytes().toString(Charsets.UTF_8)
            return p.waitFor() to text.trim()
        }
        assertEquals(3, run("cmd /c exit 3").first)
        assertEquals(0, run("Write-Output 'ok'").first)
        assertEquals(1, run("Get-Item C:\\definitely\\not\\here").first)
        val (code, text) = run("Write-Output 'Tiếng Việt \"quoted\" & pipes | ok'")
        assertEquals(0, code)
        assertEquals("Tiếng Việt \"quoted\" & pipes | ok", text)
    }

    @Test
    fun `working directory is resolved against the project`() {
        val base = System.getProperty("java.io.tmpdir")
        assertEquals(java.io.File(base).canonicalPath, ShellCommand.resolveDir(base, null)!!.canonicalPath)
        assertEquals(null, ShellCommand.resolveDir(base, "no/such/dir/here"))
    }

    @Test
    fun `paused program text`() {
        val text = DebugText.snapshot(
            header = "Debug job #2 (App) paused at src/Foo.java:42 — line breakpoint (condition: x > 3).",
            source = "     41 | for (Item item : items) {\n->   42 |   total += item.price();",
            thread = "main",
            frames = listOf(FrameLine("compute:42, Foo (com.acme)", "src/Foo.java:42"), FrameLine("main:10, App", "src/App.java:10")),
            selected = 0,
            variables = listOf(
                VarLine("item", "com.acme.Item", "{Item@456} \"Item(price=-1)\""),
                VarLine("total", "int", "17"),
                VarLine("blob", null, "x".repeat(400)),
            ),
            variablesNote = "(5 more not shown)",
        )
        assertTrue(text.contains("Stack (thread \"main\"):\n  #0 compute:42, Foo (com.acme)  [src/Foo.java:42]   <- selected\n  #1 main:10, App  [src/App.java:10]"))
        assertTrue(text.contains("\n  item (com.acme.Item) = {Item@456} \"Item(price=-1)\"\n  total (int) = 17\n"))
        assertTrue(text.contains("blob = " + "x".repeat(DebugText.MAX_VALUE - 1) + "…"))
        assertTrue(text.endsWith("(5 more not shown)"))
        val empty = DebugText.snapshot("h", null, null, emptyList(), 0, emptyList(), "(no stack frame)")
        assertEquals("h\nVariables in frame #0: (no stack frame)", empty)
        val withConsole = DebugText.snapshot("h", null, null, emptyList(), 0, emptyList(), "(no stack frame)", console = "Console:\nTotal: 90")
        assertEquals("h\nVariables in frame #0: (no stack frame)\nConsole:\nTotal: 90", withConsole)
        assertEquals(empty, DebugText.snapshot("h", null, null, emptyList(), 0, emptyList(), "(no stack frame)", console = ""))
    }

    @Test
    fun `log lines are picked by text, numbered, newest kept`() {
        val log = NumberedLines.of("INFO start\nWARN slow\r\nERROR boom\nINFO ok\nerror again\n")
        val (hits, count) = LogText.grep(log, "error", null, 5)
        assertEquals(2, count)
        assertEquals("3: ERROR boom\n5: error again", hits)
        assertEquals("5: error again", LogText.grep(log, "ERROR", null, 1).first)
        assertEquals("5: error again", LogText.grep(log, "error", 4, 5).first)
        assertEquals(0, LogText.grep(log, "missing", null, 5).second)
    }

    @Test
    fun `lines keep their numbers after old output is dropped, progress rewrites collapse`() {
        val out = OutputBuffer(limit = 30)
        out.append("a\r\nstep 1\rstep 2\r") // "\r" then "\n" in the next chunk is still one line break
        out.append("\nb")
        val small = out.numbered()
        assertEquals(listOf("a", "step 2", "b"), small.lines)
        assertEquals(3L, small.total)
        assertEquals(2L, small.complete) // "b" is still being written
        repeat(10) { out.append("\nline $it") }
        val log = out.numbered()
        assertEquals(13L, log.total)
        assertEquals(13L, out.lineCount)
        assertEquals("line 9", log.lines.last())
        // a=1, step 2=2, b=3, "line k"=4+k; the last 30 characters start inside "line 5" (line 9).
        assertEquals(9L, log.first)
        assertEquals("line 6", log.lines[1])
        assertEquals("…\nline 8\nline 9", out.tail(2))
    }

    @Test
    fun `reading again gives only the new lines`() {
        val out = OutputBuffer()
        repeat(100) { out.append("line ${it + 1}\n") }
        val r = LogReader()
        val first = r.read("chat", "run-1", "Run #1 App", out.numbered(), lines = 5)
        assertEquals("Run #1 App: lines 96-100 of 100:\nline 96\nline 97\nline 98\nline 99\nline 100", first)
        assertEquals("Run #1 App: nothing new since line 100 (that is all of it).", r.read("chat", "run-1", "Run #1 App", out.numbered(), 5, newOnly = true))
        repeat(8) { out.append("line ${it + 101}\n") }
        val next = r.read("chat", "run-1", "Run #1 App", out.numbered(), 5, newOnly = true)
        assertEquals("Run #1 App: lines 101-105 of 108:\nline 101\nline 102\nline 103\nline 104\nline 105\n[3 more lines; next: from_line=106, or new_only]", next)
        assertTrue(r.read("chat", "run-1", "Run #1 App", out.numbered(), 5, newOnly = true).startsWith("Run #1 App: lines 106-108 of 108:"))
        // Another chat has its own place; from_line reads anywhere.
        assertTrue(r.read("other", "run-1", "Run #1 App", out.numbered(), 5, newOnly = true).startsWith("Run #1 App: lines 104-108 of 108:"))
        assertTrue(r.read("chat", "run-1", "Run #1 App", out.numbered(), 2, fromLine = 10).startsWith("Run #1 App: lines 10-11 of 108:\nline 10\nline 11\n[97 more"))
        assertEquals("Run #1 App: no output yet.", r.read("chat", "run-2", "Run #1 App", OutputBuffer().numbered(), 5))
    }

    @Test
    fun `debug stops show only console lines not seen yet`() {
        val out = OutputBuffer()
        val r = LogReader()
        assertEquals(null, r.newest("chat", "job-3", out.numbered(), 10))
        repeat(25) { out.append("tick ${it + 1}\n") }
        val (w, firstNew) = r.newest("chat", "job-3", out.numbered(), 10)!!
        assertEquals(1L, firstNew)
        assertEquals(16L to 25L, w.first to w.last)
        assertEquals(null, r.newest("chat", "job-3", out.numbered(), 10)) // a step that printed nothing
        out.append("tick 26\n")
        assertEquals("tick 26", r.newest("chat", "job-3", out.numbered(), 10)!!.first.text)
        // job_status new_only carries on from the same place.
        assertTrue(r.read("chat", "job-3", "output", out.numbered(), 40, newOnly = true).startsWith("output: nothing new since line 26"))
    }
}
