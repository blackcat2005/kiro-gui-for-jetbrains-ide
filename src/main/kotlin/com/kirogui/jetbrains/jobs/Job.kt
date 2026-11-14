package com.kirogui.jetbrains.jobs

import java.util.concurrent.atomic.AtomicBoolean

enum class JobKind { COMMAND, DEBUG, TEST, TASK }

enum class JobState(val label: String) {
    STARTING("starting"),
    RUNNING("running"),
    PAUSED("paused"),
    FINISHED("finished"),
    FAILED("failed"),
    STOPPED("stopped");

    val active: Boolean get() = this == STARTING || this == RUNNING || this == PAUSED
}

/**
 * Something the IDE runs on Kiro's behalf while the chat goes on: a shell command, or a program under the
 * debugger. It belongs to the chat ([sessionId]) that started it; that chat is woken when the job needs attention.
 */
class Job(
    val id: Int,
    val kind: JobKind,
    /** Short label for lists and the Run tool window tab. */
    val title: String,
    /** The command line, or the run configuration's name. */
    val detail: String,
    val sessionId: String?,
    val startedAt: Long = System.currentTimeMillis(),
) {
    @Volatile var state: JobState = JobState.STARTING
    @Volatile var exitCode: Int? = null
    @Volatile var endedAt: Long? = null
    @Volatile var error: String? = null
    val output = OutputBuffer()

    /** Kiro asked to stop it (so it already knows; no wake-up). */
    val stoppedByKiro = AtomicBoolean(false)

    /** The user pressed Stop in the IDE. */
    val stoppedByUser = AtomicBoolean(false)

    /** Stops the process; set once it runs. */
    @Volatile var stopper: (() -> Unit)? = null

    /** The running process, so the Jobs tab can bring its console to the front. */
    @Volatile var process: com.intellij.execution.process.ProcessHandler? = null

    /** A `debug_task` job: the Kiro session its helper runs in, so the whole conversation can be read or attached. */
    @Volatile var helperSessionId: String? = null

    /** Once ended, a better outcome than the exit code (a test run: "3 of 44 tests failed"). */
    @Volatile var summary: String? = null

    /** Once ended, what Kiro is told instead of the output's last lines (a test run's report). */
    @Volatile var report: String? = null

    /** While it runs, how far it got ("120 tests done so far, 2 failed"). */
    @Volatile var progress: (() -> String?)? = null

    fun end(state: JobState, exitCode: Int?, now: Long = System.currentTimeMillis()) {
        this.exitCode = exitCode
        this.endedAt = now
        this.state = state
    }

    fun elapsedMillis(now: Long = System.currentTimeMillis()): Long = (endedAt ?: now) - startedAt
}

/**
 * The tail of a job's output. Keeps the last [limit] characters, but numbers lines from the start of the run, so a
 * line keeps its number after older ones were dropped. A line rewritten with `\r` (progress bars) counts as its final
 * text.
 */
class OutputBuffer(private val limit: Int = 256 * 1024) {
    private val text = StringBuilder()
    private var dropped = 0L
    private var droppedLines = 0L

    @Synchronized
    fun append(chunk: String) {
        text.append(chunk.replace("\r\n", "\n"))
        if (text.length > limit) {
            val cut = text.length - limit
            for (i in 0 until cut) if (text[i] == '\n') droppedLines++
            text.delete(0, cut)
            dropped += cut
        }
    }

    @get:Synchronized
    val totalChars: Long get() = dropped + text.length

    /** Lines printed so far, the dropped ones included. */
    @get:Synchronized
    val lineCount: Long get() = droppedLines + text.count { it == '\n' } + if (text.isNotEmpty() && !text.endsWith("\n")) 1 else 0

    /** The kept lines with their numbers. */
    @Synchronized
    fun numbered(): NumberedLines = NumberedLines.of(text.toString(), droppedLines + 1)

    /** The last [lines] lines, at most [maxChars] characters; "…" marks that earlier output was left out. */
    @Synchronized
    fun tail(lines: Int, maxChars: Int = 6000): String {
        val log = numbered()
        if (log.lines.isEmpty()) return ""
        val w = LogText.window(log, null, lines, maxChars, newest = true)
        return if (w.first > 1) "…\n${w.text}" else w.text
    }
}

/** Wording shared by tool results and wake-up messages. */
object JobText {

    fun duration(millis: Long): String {
        val s = (millis / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    fun outcome(job: Job): String = job.summary?.takeIf { !job.state.active } ?: when (job.state) {
        JobState.FINISHED -> "finished, exit code ${job.exitCode ?: 0}"
        JobState.FAILED -> if (job.exitCode != null) "failed, exit code ${job.exitCode}" else "failed: ${job.error ?: "could not start"}"
        JobState.STOPPED -> if (job.stoppedByUser.get()) "stopped by the user" else "stopped"
        JobState.PAUSED -> "paused in the debugger"
        JobState.RUNNING -> "running"
        JobState.STARTING -> "starting"
    }

    fun noun(job: Job) = when (job.kind) {
        JobKind.DEBUG -> "Debug job"
        JobKind.TEST -> "Test job"
        JobKind.COMMAND -> "Background job"
        JobKind.TASK -> "Debug helper job"
    }

    /** One line per job for listings. */
    fun line(job: Job, now: Long = System.currentTimeMillis()): String =
        "#${job.id} [${job.state.label}] ${job.title} — ${outcome(job)}, ${duration(job.elapsedMillis(now))}"

    /** The first lines of `job_status`: state, how long, what it runs. */
    fun header(job: Job, now: Long = System.currentTimeMillis()): String =
        "${noun(job)} #${job.id}: ${outcome(job)} after ${duration(job.elapsedMillis(now))}.\n" + what(job)

    fun what(job: Job) = when (job.kind) {
        JobKind.COMMAND -> "command: "
        JobKind.TASK -> "task: "
        else -> "configuration: "
    } + job.detail

    /**
     * The wake-up text when a job has ended on its own (or was stopped by the user): its [Job.report] if it has one,
     * else the outcome with the output's last [lines] lines.
     */
    fun ended(job: Job, lines: Int = 60, now: Long = System.currentTimeMillis()): String = job.report ?: buildString {
        append("${noun(job)} #${job.id} ${outcome(job)} after ${duration(job.elapsedMillis(now))}.\n").append(what(job)).append('\n')
        val log = job.output.numbered()
        if (log.lines.isEmpty()) {
            append("output: (none)")
            return@buildString
        }
        val w = LogText.window(log, null, lines, 6000, newest = true)
        append("output, lines ${w.first}-${w.last} of ${w.total}")
        if (w.first > 1) append(" (job_status with from_line reads the earlier ones)")
        append(":\n").append(w.text)
    }

    /** One line for the chat transcript and notifications. */
    fun endedSummary(job: Job): String = "${noun(job)} #${job.id} (${job.title}) ${outcome(job)}"
}
