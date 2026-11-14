package com.kirogui.jetbrains.jobs

import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.SystemInfo
import com.kirogui.jetbrains.mcp.McpCall
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.argString
import com.kirogui.jetbrains.service.FileSync
import java.io.File
import com.intellij.util.execution.ParametersListUtil
import java.util.Base64

/** How a command line is handed to the shell. Pure, so it can be tested without an IDE. */
object ShellCommand {

    /** First word of a shell template that stands for "wherever Git Bash is installed". */
    const val GIT_BASH = "git-bash"

    fun shellName(windows: Boolean, template: String = ""): String {
        val first = ParametersListUtil.parse(template.trim()).firstOrNull()
        if (first != null) {
            val name = first.substringAfterLast('/').substringAfterLast('\\').removeSuffix(".exe")
            return if (name.equals(GIT_BASH, true)) "Git Bash" else name
        }
        return if (windows) "Windows PowerShell 5.1" else if (File("/bin/bash").exists()) "bash" else "sh"
    }

    /**
     * With a [template] (`bash -c {cmd}`, `cmd /c {cmd}`, `git-bash -c {cmd}`) the command is that line with `{cmd}`
     * replaced by the command, as one argument; without `{cmd}` the command is appended. [gitBash] says where Git
     * Bash is for the `git-bash` word. Blank template: the default shell.
     *
     * Default, Windows: PowerShell with the script base64-encoded (no quoting problems), UTF-8 output, and the exit
     * code of the last native command passed on (`-Command` alone would turn every failure into 1). Elsewhere:
     * `bash -c`.
     */
    fun build(command: String, windows: Boolean, template: String = "", gitBash: () -> String? = ::findGitBash): List<String> {
        val tokens = ParametersListUtil.parse(template.trim())
        if (tokens.isNotEmpty()) {
            val exe = if (tokens.first().equals(GIT_BASH, true)) {
                gitBash() ?: throw IllegalStateException("Git Bash was not found. Install Git for Windows or give the full path to bash.exe in Settings → Tools → Kiro.")
            } else tokens.first()
            val rest = tokens.drop(1)
            return listOf(exe) + if ("{cmd}" in rest) rest.map { if (it == "{cmd}") command else it } else rest + command
        }
        if (!windows) return listOf(if (File("/bin/bash").exists()) "/bin/bash" else "/bin/sh", "-c", command)
        val script = listOf(
            "\$ProgressPreference = 'SilentlyContinue'",
            "try { [Console]::OutputEncoding = New-Object System.Text.UTF8Encoding \$false } catch {}",
            "\$OutputEncoding = New-Object System.Text.UTF8Encoding \$false",
            command,
            "if (-not \$?) { if (\$LASTEXITCODE) { exit \$LASTEXITCODE } else { exit 1 } }",
            "exit 0",
        ).joinToString("\n")
        val encoded = Base64.getEncoder().encodeToString(script.toByteArray(Charsets.UTF_16LE))
        return listOf("powershell.exe", "-NoLogo", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded)
    }

    /** `bash.exe` of Git for Windows: the usual install folders, else next to `git` on the PATH. Null elsewhere or if absent. */
    fun findGitBash(env: Map<String, String> = System.getenv()): String? {
        val roots = listOfNotNull(
            env["ProgramFiles"], env["ProgramW6432"], env["ProgramFiles(x86)"], env["LOCALAPPDATA"]?.let { "$it\\Programs" },
        ).map { File(it, "Git") }
        val fromPath = (env["PATH"] ?: env["Path"]).orEmpty().split(File.pathSeparatorChar)
            .filter { it.isNotBlank() }.map { File(it) }
            .firstOrNull { File(it, "git.exe").isFile }
            ?.parentFile?.let { if (it.name.equals("cmd", true) || it.name.equals("bin", true)) it.parentFile else it }
        return (roots + listOfNotNull(fromPath))
            .flatMap { listOf(File(it, "bin/bash.exe"), File(it, "usr/bin/bash.exe")) }
            .firstOrNull { it.isFile }?.path
    }

    /** [cwd] relative to [base] or absolute; null when it isn't a directory. */
    fun resolveDir(base: String, cwd: String?): File? {
        val dir = when {
            cwd.isNullOrBlank() -> File(base)
            File(cwd).isAbsolute -> File(cwd)
            else -> File(base, cwd)
        }
        return dir.takeIf { it.isDirectory }
    }
}

/** `bg_run`: shell commands that run in the IDE (Run tool window) while the chat goes on. */
class ShellJobs(private val project: Project, private val jobs: JobService) {

    private val log = Logger.getInstance(ShellJobs::class.java)
    private val base get() = project.basePath ?: System.getProperty("user.home")

    /** The shell the user picked in the settings for Kiro's commands; blank = PowerShell on Windows, bash elsewhere. */
    private fun shellTemplate() = com.kirogui.jetbrains.settings.KiroSettings.getInstance().state.jobShell

    fun tools(): List<McpTool> = listOf(
        McpTool(
            name = "bg_run",
            description = "Start a shell command in the background in the IDE and return at once with a job id. Use it for " +
                "anything slow: builds, test suites, dev servers, long scripts. When the command ends you automatically " +
                "get a message with the exit code and the end of its output, so do not poll, sleep or wait for it: end " +
                "your turn or carry on with other work. The command runs in ${ShellCommand.shellName(SystemInfo.isWindows, shellTemplate())} " +
                "in the project folder (or `cwd`), and the user sees it live in the IDE's Run tool window. " +
                "Use job_status to look at the output while it runs and job_stop to end it.",
            inputSchema = Schema.obj(
                listOf("command"),
                "command" to Schema.string("The command line to run."),
                "cwd" to Schema.string("Working directory, relative to the project folder or absolute. Default: the project folder."),
                "title" to Schema.string("A few words naming the job for the user, e.g. \"unit tests\"."),
            ),
            call = ::start,
        ),
    )

    private fun start(call: McpCall): McpResult {
        val command = call.arguments.argString("command") ?: return McpResult("`command` is required.", isError = true)
        val cwd = call.arguments.argString("cwd")
        val dir = ShellCommand.resolveDir(base, cwd) ?: return McpResult("Not a directory: $cwd", isError = true)
        val title = call.arguments.argString("title") ?: command.lineSequence().first().let { if (it.length > 60) it.take(59) + "…" else it }
        val job = jobs.newJob(JobKind.COMMAND, title, command, call.sessionId)

        val handler = try {
            val line = GeneralCommandLine(ShellCommand.build(command, SystemInfo.isWindows, shellTemplate()))
                .withWorkDirectory(dir)
                .withCharset(Charsets.UTF_8)
                .withEnvironment("KIRO_GUI_JOB", job.id.toString())
            KillableColoredProcessHandler(line.createProcess(), command, Charsets.UTF_8)
        } catch (e: Exception) {
            job.error = e.message ?: e.javaClass.simpleName
            job.end(JobState.FAILED, null)
            jobs.changed()
            return McpResult("Could not start the command: ${job.error}", isError = true)
        }
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // SYSTEM is the IDE's own lines (the command, "Process finished ..."), not the program's output.
                if (outputType != ProcessOutputTypes.SYSTEM) job.output.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) = finished(job, event.exitCode)
        })
        job.stopper = { handler.destroyProcess() }
        job.process = handler
        job.state = JobState.RUNNING
        jobs.changed()

        ApplicationManager.getApplication().invokeLater({
            try {
                RunContentExecutor(project, handler)
                    .withTitle("Kiro #${job.id}: $title")
                    .withActivateToolWindow(false)
                    .withStop({
                        job.stoppedByUser.set(true)
                        handler.destroyProcess()
                    }, { !handler.isProcessTerminated })
                    .run() // also starts reading the output
            } catch (e: Exception) {
                log.warn("Could not show job #${job.id} in the Run tool window", e)
                if (!handler.isStartNotified) handler.startNotify()
            }
        }, ModalityState.defaultModalityState())

        return McpResult(
            "Started background job #${job.id} (${title}) in ${dir.path}. You will get a message with the exit code and " +
                "output when it ends; do not poll, sleep or wait for it.",
        )
    }

    private fun finished(job: Job, exitCode: Int) {
        val state = when {
            job.stoppedByKiro.get() || job.stoppedByUser.get() -> JobState.STOPPED
            exitCode == 0 -> JobState.FINISHED
            else -> JobState.FAILED
        }
        job.end(state, exitCode)
        jobs.changed()
        // A command (a formatter, a code generator, git) may have changed files the editor has open.
        FileSync.syncAll()
        // Kiro stopped it itself, so it already knows.
        if (!job.stoppedByKiro.get()) jobs.ended(job)
    }
}
