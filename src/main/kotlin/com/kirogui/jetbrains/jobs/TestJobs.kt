package com.kirogui.jetbrains.jobs

import com.intellij.build.BuildProgressListener
import com.intellij.build.BuildViewManager
import com.intellij.build.events.FileMessageEvent
import com.intellij.build.events.MessageEvent
import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Location
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.PsiLocation
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ExecutionEnvironmentBuilder
import com.intellij.execution.runners.ProgramRunner
import com.intellij.execution.testframework.sm.runner.SMRunnerConsolePropertiesProvider
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsAdapter
import com.intellij.execution.testframework.sm.runner.SMTRunnerEventsListener
import com.intellij.execution.testframework.sm.runner.SMTestProxy
import com.intellij.execution.testframework.sm.runner.states.TestStateInfo
import com.intellij.execution.testframework.sm.runner.ui.SMTRunnerConsoleView
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.module.ModuleUtilCore
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.GlobalSearchScope
import com.kirogui.jetbrains.mcp.McpCall
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.argInt
import com.kirogui.jetbrains.mcp.argString
import com.kirogui.jetbrains.service.IdePaths
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/** One failed test, as Kiro is told about it. */
class TestFailure(val name: String, val location: String?, val message: String, val frames: List<String>)

class TestTally(val passed: Int, val failed: Int, val ignored: Int, val notRun: Int) {
    val total: Int get() = passed + failed + ignored + notRun
}

/** Wording of test reports. Pure, so it is testable. */
object TestText {
    const val MAX_FAILURES = 20

    /** Stack frames of test frameworks, the JDK and the IDE's runners: not where a bug is. */
    private val NOISE = listOf(
        "org.junit.", "junit.", "org.opentest4j.", "org.testng.", "java.", "javax.", "jdk.", "sun.", "kotlin.test.",
        "kotlin.coroutines.", "kotlinx.coroutines.", "org.gradle.", "worker.org.gradle.", "com.intellij.", "org.assertj.",
        "org.hamcrest.", "org.mockito.internal.", "org.spockframework.", "org.apache.maven.surefire.", "groovy.", "org.codehaus.groovy.",
    )

    /** The frames worth reading: JVM frames outside the frameworks (the test and the code under test), else the end of the trace. */
    fun frames(stacktrace: String?, max: Int = 5): List<String> {
        if (stacktrace.isNullOrBlank()) return emptyList()
        val lines = stacktrace.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val at = lines.filter { it.startsWith("at ") }
        if (at.isEmpty()) return lines.drop(1).takeLast(max) // not a JVM trace (Python, JavaScript, …)
        val own = at.filter { f -> NOISE.none { f.removePrefix("at ").startsWith(it) } }
        val cause = lines.lastOrNull { it.startsWith("Caused by:") }
        return own.ifEmpty { at }.take(max) + listOfNotNull(cause)
    }

    /** The assertion message, a few lines at most. */
    fun message(errorMessage: String?, stacktrace: String?): String {
        val text = errorMessage?.trim().orEmpty().ifEmpty { stacktrace?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim().orEmpty() }
        val lines = text.lines()
        val kept = lines.take(6).joinToString("\n") + if (lines.size > 6) "\n…" else ""
        return if (kept.length > 800) kept.take(799) + "…" else kept
    }

    /** "3 of 44 tests failed" and the like. */
    fun outcome(t: TestTally, stopped: Boolean): String = when {
        t.total == 0 -> if (stopped) "stopped before any test ran" else "no tests ran"
        t.failed > 0 -> "${t.failed} of ${t.total} tests failed"
        stopped -> "stopped after ${t.passed} of ${t.total} tests passed"
        t.notRun > 0 -> "${t.passed} of ${t.total} tests passed, ${t.notRun} did not run"
        else -> "all ${t.passed} tests passed" + if (t.ignored > 0) " (${t.ignored} ignored)" else ""
    }

    fun counts(t: TestTally): String = listOf(
        "${t.passed} passed", "${t.failed} failed",
        if (t.ignored > 0) "${t.ignored} ignored" else null,
        if (t.notRun > 0) "${t.notRun} not run" else null,
    ).filterNotNull().joinToString(", ")

    fun report(head: String, configuration: String, t: TestTally, failures: List<TestFailure>, jobId: Int, note: String? = null): String = buildString {
        append(head)
        if (t.total > 0) append(' ').append(counts(t)).append('.')
        append("\nrun configuration: ").append(configuration)
        note?.let { append('\n').append(it) }
        if (failures.isNotEmpty()) {
            append("\nFailed:")
            failures.take(MAX_FAILURES).forEachIndexed { i, f ->
                append('\n').append(i + 1).append(". ").append(f.name)
                f.location?.let { append("  [").append(it).append(']') }
                f.message.takeIf { it.isNotBlank() }?.lines()?.forEach { append("\n     ").append(it) }
                f.frames.forEach { append("\n     ").append(it) }
            }
            if (failures.size > MAX_FAILURES) append("\n(${failures.size - MAX_FAILURES} more failed tests not shown)")
            append("\nNext: fix the cause (the code or the test, whichever is wrong), rerun only these with ")
            append("test_run rerun_failed=$jobId, then run all tests once more.")
        }
        append("\n(Console output: job_status id=$jobId, with contains or from_line.)")
    }

    /** The report when the build failed and no test ran. */
    fun buildFailed(head: String, configuration: String, errors: List<String>, jobId: Int): String = buildString {
        append(head)
        append("\nrun configuration: ").append(configuration)
        if (errors.isEmpty()) {
            append("\nThe IDE could not build or start it; the Build tool window has the details.")
        } else {
            append("\nCompile errors:")
            errors.take(30).forEach { append("\n  ").append(it) }
            if (errors.size > 30) append("\n  (${errors.size - 30} more)")
            append("\nFix them, then run test_run again.")
        }
        append("\n(Console output, if any: job_status id=$jobId.)")
    }
}

/**
 * `test_run`: tests run with the IDE's own test runner, whatever the project uses (JUnit, TestNG, Gradle, pytest, Jest,
 * …), found the way the editor's "Run tests" actions find them. When the run ends Kiro gets a report built from the
 * test tree (which tests failed, where, the assertion and the frames in the project's code), or the compile errors.
 */
class TestJobs(private val project: Project, private val jobs: JobService) {

    private class TestRun(val job: Job, val settings: RunnerAndConfigurationSettings) {
        @Volatile var env: ExecutionEnvironment? = null
        @Volatile var handler: ProcessHandler? = null
        @Volatile var root: SMTestProxy.SMRootTestProxy? = null
        val finished = AtomicBoolean(false)
        val outputAttached = AtomicBoolean(false)
        val buildErrors = CopyOnWriteArrayList<String>()

        /** The failed tests of this run, for `rerun_failed`. */
        @Volatile var failed: List<SMTestProxy> = emptyList()
    }

    private class Fail(message: String) : Exception(message)

    private val log = Logger.getInstance(TestJobs::class.java)
    private val runs = ConcurrentHashMap<Int, TestRun>()

    init {
        val bus = project.messageBus.connect(jobs)
        bus.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStarting(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                runFor(env)?.let { attachProcess(it, handler) }
            }

            override fun processStarted(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler) {
                runFor(env)?.let { attachProcess(it, handler) }
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                runFor(env)?.let { notStarted(it, null) }
            }
        })
        bus.subscribe(SMTRunnerEventsListener.TEST_STATUS, object : SMTRunnerEventsAdapter() {
            override fun onTestingStarted(testsRoot: SMTestProxy.SMRootTestProxy) {
                byRoot(testsRoot)?.let { if (it.root == null) it.root = testsRoot }
            }

            override fun onTestingFinished(testsRoot: SMTestProxy.SMRootTestProxy) {
                byRoot(testsRoot)?.let {
                    if (it.root == null) it.root = testsRoot
                    finishSoon(it, waitForTree = false)
                }
            }
        })
        // Compile errors of the build that runs before the tests.
        try {
            project.getService(BuildViewManager::class.java)?.addListener(BuildProgressListener { _, event ->
                if (event is MessageEvent && event.kind == MessageEvent.Kind.ERROR) {
                    val building = runs.values.filter { it.job.state == JobState.STARTING }
                    if (building.isNotEmpty()) {
                        val where = (event as? FileMessageEvent)?.filePosition?.let { p ->
                            p.file?.let { "${relative(it.path)}:${p.startLine + 1}: " }
                        }.orEmpty()
                        val text = where + event.message.trim()
                        building.forEach { if (it.buildErrors.size < 100 && text !in it.buildErrors) it.buildErrors.add(text) }
                    }
                }
            }, jobs)
        } catch (e: Exception) {
            log.info("Build messages are not available; a failed build is reported without its errors", e)
        }
    }

    fun tools(): List<McpTool> = listOf(
        McpTool(
            name = "test_run",
            description = "Run tests with the IDE's test runner (JUnit, TestNG, Gradle, pytest, Jest, … whatever the project " +
                "uses) and return at once with a job id. Without `target` it runs all tests in the project. The IDE " +
                "builds first. When the run ends you automatically get a report: how many passed and failed and, for each " +
                "failure, the test, its file and line, the assertion message and the stack frames in the project's code; " +
                "or the compile errors if the build failed. Do not poll, sleep or wait: end your turn or do other work. " +
                "To fix failing tests: read the report, decide whether the code or the test is wrong, fix it, rerun just " +
                "the failures with rerun_failed, and finally run all tests once more. The user sees the run in the Run " +
                "tool window.",
            inputSchema = Schema.obj(
                emptyList(),
                "target" to Schema.string(
                    "What to run: a test file or directory (relative to the project), `file:line` for the single test at " +
                        "that line, or the name of a test run configuration. Default: all tests in the project.",
                ),
                "rerun_failed" to Schema.integer("Job id of an earlier test run: run only the tests that failed in it."),
            ),
            call = ::start,
        ),
    )

    // ---------- test_run ----------

    private fun start(call: McpCall): McpResult {
        if (DumbService.isDumb(project)) {
            return McpResult("The IDE is still indexing the project, so it can't find the tests yet. Try again in a minute.", isError = true)
        }
        val target = call.arguments.argString("target")?.trim()?.takeIf { it.isNotEmpty() }
        val rerun = call.arguments.argInt("rerun_failed")
        // A file Kiro has just written may not be known to the IDE yet (refreshing is not allowed under a read action).
        target?.let { pathOf(it) }?.let { LocalFileSystem.getInstance().refreshAndFindFileByPath(IdePaths.normalize(it.path)) }
        // Finding the tests reads PSI and indexes: in a background read action, as the editor's own Run actions do.
        val (settings, title, note) = try {
            runReadAction { resolve(target, rerun) }
        } catch (e: Fail) {
            return McpResult(e.message ?: "Can't run that.", isError = true)
        } catch (e: Exception) {
            log.info("test_run could not find the tests", e)
            return McpResult("Could not work out which tests to run: ${e.message ?: e.javaClass.simpleName}", isError = true)
        }
        val launched = CompletableFuture<Any>() // TestRun, or the reason it can't run
        ApplicationManager.getApplication().invokeLater({
            launched.complete(
                try {
                    launch(settings, title, note, call.sessionId)
                } catch (e: Fail) {
                    e.message ?: "Can't run that."
                } catch (e: Exception) {
                    log.warn("test_run failed", e)
                    e.message ?: e.javaClass.simpleName
                },
            )
        }, ModalityState.defaultModalityState())
        val outcome = try {
            launched.get(30, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            return McpResult("The IDE is busy; the test run is still being prepared. You will get the report when it ends.")
        }
        if (outcome is String) return McpResult(outcome, isError = true)
        val run = outcome as TestRun
        return McpResult(
            "Started test job #${run.job.id}: ${run.settings.name} (${run.settings.type.displayName}). The IDE builds, then " +
                "runs the tests in the Run tool window. You will get the report when it ends; do not poll, sleep or wait." +
                (run.job.error?.let { "\n$it" } ?: ""),
        )
    }

    /** `target` as a file, `file:line` without the line. */
    private fun pathOf(target: String): File {
        val path = Regex("^(.+?):(\\d+)$").find(target)?.groupValues?.get(1) ?: target
        return File(path).let { if (it.isAbsolute) it else File(project.basePath ?: ".", path) }
    }

    /** What to run: the settings, the job title, and a note for Kiro (e.g. that a rerun could not be narrowed). Under a read action. */
    private fun resolve(target: String?, rerun: Int?): Triple<RunnerAndConfigurationSettings, String, String?> {
        if (rerun != null) {
            val previous = runs[rerun] ?: throw Fail("There is no test job #$rerun.")
            val failed = previous.failed
            if (failed.isEmpty()) throw Fail("Test job #$rerun has no failed tests" + if (previous.job.state.active) " yet (it is still running)." else ".")
            val scope = GlobalSearchScope.allScope(project)
            val elements = failed.mapNotNull { t -> runCatching { t.getLocation(project, scope)?.psiElement }.getOrNull() }.distinct()
            val narrowed = elements.takeIf { it.isNotEmpty() }?.let(::fromElements)
            return if (narrowed != null) Triple(narrowed, "failed tests of #$rerun", null)
            else Triple(previous.settings, previous.job.title, "Could not pick out only the failed tests, so all of \"${previous.settings.name}\" runs again.")
        }
        if (target != null) {
            RunManager.getInstance(project).findConfigurationByName(target)?.let { return Triple(it, it.name, null) }
            return Triple(fromPath(target), target, null)
        }
        return Triple(wholeProject(), "all tests", null)
    }

    private fun fromPath(target: String): RunnerAndConfigurationSettings {
        val line = Regex("^(.+?):(\\d+)$").find(target)?.groupValues?.get(2)?.toIntOrNull()
        val vf = LocalFileSystem.getInstance().findFileByPath(IdePaths.normalize(pathOf(target).path))
            ?: throw Fail("There is no file or test run configuration \"$target\".\n" + testConfigurations())
        val psi = PsiManager.getInstance(project)
        if (vf.isDirectory) {
            val dir = psi.findDirectory(vf) ?: throw Fail("Can't read the directory $target.")
            return fromElements(listOf(dir)) ?: throw Fail("No tests to run in $target.")
        }
        val psiFile = psi.findFile(vf) ?: throw Fail("Can't read $target.")
        if (line != null) {
            val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile) ?: FileDocumentManager.getInstance().getDocument(vf)
                ?: throw Fail("Can't read $target.")
            val l = (line - 1).coerceIn(0, (doc.lineCount - 1).coerceAtLeast(0))
            var offset = doc.getLineStartOffset(l)
            val end = doc.getLineEndOffset(l)
            while (offset < end && doc.charsSequence[offset].isWhitespace()) offset++
            val element = psiFile.findElementAt(offset) ?: psiFile
            return fromElements(listOf(element)) ?: throw Fail("No test at $target. Give the line of a test method, or just the file.")
        }
        // The file itself, else its top-level declarations (in Java the class is what "Run tests" works on).
        val candidates = listOf<PsiElement>(psiFile) + psiFile.children.filterIsInstance<PsiNamedElement>()
        for (c in candidates) fromElements(listOf(c))?.let { return it }
        throw Fail("No tests to run in $target.")
    }

    private fun wholeProject(): RunnerAndConfigurationSettings {
        val base = project.basePath?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val psi = PsiManager.getInstance(project)
        base?.let { psi.findDirectory(it) }?.let { dir -> fromElements(listOf(dir))?.let { return it } }
        // No runner for the project folder: try the test source folders.
        val index = ProjectFileIndex.getInstance(project)
        val roots = ProjectRootManager.getInstance(project).contentSourceRoots.filter { index.isInTestSourceContent(it) }
        val dirs = roots.mapNotNull { psi.findDirectory(it) }
        if (dirs.isNotEmpty()) fromElements(dirs)?.let { return it }
        if (dirs.size > 1) fromElements(dirs.take(1))?.let { return it }
        throw Fail(
            "Couldn't work out how to run all the tests of this project. Give `target` (a test folder or file), or ask the " +
                "user for a run configuration that runs them.\n" + testConfigurations(),
        )
    }

    /** The test configuration the editor's "Run" would make for these elements; null if no test runner applies. */
    private fun fromElements(elements: List<PsiElement>): RunnerAndConfigurationSettings? {
        val first = elements.first()
        val builder = SimpleDataContext.builder()
            .add(CommonDataKeys.PROJECT, project)
            .add(CommonDataKeys.PSI_ELEMENT, first)
            .add(Location.DATA_KEY, PsiLocation.fromPsiElement(first))
        ModuleUtilCore.findModuleForPsiElement(first)?.let { builder.add(LangDataKeys.MODULE, it) }
        if (elements.size > 1) {
            @Suppress("DEPRECATION")
            builder.add(LangDataKeys.PSI_ELEMENT_ARRAY, elements.toTypedArray())
            builder.add(Location.DATA_KEYS, elements.map { PsiLocation.fromPsiElement(it) }.toTypedArray())
        }
        val context = ConfigurationContext.getFromContext(builder.build(), ActionPlaces.UNKNOWN)
        val made = try {
            context.configurationsFromContext.orEmpty()
        } catch (e: Exception) {
            log.info("No configuration from context", e)
            emptyList()
        }
        return made.map { it.configurationSettings }.firstOrNull { it.configuration is SMRunnerConsolePropertiesProvider }
    }

    private fun testConfigurations(): String {
        val tests = RunManager.getInstance(project).allSettings.filter { it.configuration is SMRunnerConsolePropertiesProvider }
        return if (tests.isEmpty()) "The project has no test run configurations."
        else "Test run configurations:\n" + tests.joinToString("\n") { "  ${it.name} (${it.type.displayName})" }
    }

    private fun launch(settings: RunnerAndConfigurationSettings, title: String, note: String?, sessionId: String?): TestRun {
        val job = jobs.newJob(JobKind.TEST, title, settings.name, sessionId)
        job.error = note
        val run = TestRun(job, settings)
        runs[job.id] = run
        job.stopper = { run.handler?.takeUnless { it.isProcessTerminated }?.destroyProcess() }
        job.progress = { run.root?.let { r -> tally(r).let { t -> "${t.passed + t.failed + t.ignored} tests done so far, ${t.failed} failed" } } }
        jobs.changed()
        try {
            val builder = ExecutionEnvironmentBuilder.createOrNull(DefaultRunExecutor.getRunExecutorInstance(), settings)
                ?: throw Fail("\"${settings.name}\" can't be run.")
            val env = builder.build(object : ProgramRunner.Callback {
                override fun processStarted(descriptor: RunContentDescriptor?) {
                    descriptor ?: return
                    (descriptor.executionConsole as? SMTRunnerConsoleView)?.resultsViewer?.testsRootNode?.let { run.root = it }
                    descriptor.processHandler?.let { attachProcess(run, it) }
                }

                override fun processNotStarted(error: Throwable?) = notStarted(run, error?.message)
            })
            run.env = env
            ProgramRunnerUtil.executeConfiguration(env, false, true)
        } catch (e: Exception) {
            runs.remove(job.id)
            job.error = e.message
            job.end(JobState.FAILED, null)
            jobs.changed()
            throw e
        }
        return run
    }

    // ---------- following the run ----------

    private fun runFor(env: ExecutionEnvironment): TestRun? = runs.values.firstOrNull { it.env === env }

    private fun byRoot(root: SMTestProxy.SMRootTestProxy): TestRun? =
        runs.values.firstOrNull { it.root === root || (it.handler != null && root.handler === it.handler) }

    private fun attachProcess(run: TestRun, handler: ProcessHandler) {
        run.handler = handler
        run.job.process = handler
        if (!run.outputAttached.compareAndSet(false, true)) return
        handler.addProcessListener(object : ProcessListener {
            override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                // The test runner's own messages to the IDE, not output.
                if (!event.text.trimStart().startsWith("##teamcity[")) run.job.output.append(event.text)
            }

            override fun processTerminated(event: ProcessEvent) = finishSoon(run, waitForTree = true)
        })
        if (run.job.state == JobState.STARTING) {
            run.job.state = JobState.RUNNING
            jobs.changed()
        }
        if (run.job.stoppedByKiro.get() || run.job.stoppedByUser.get()) handler.destroyProcess()
    }

    private fun notStarted(run: TestRun, reason: String?) {
        if (run.finished.get()) return
        // Build messages can arrive a moment after the build gave up.
        ApplicationManager.getApplication().executeOnPooledThread {
            Thread.sleep(800)
            if (!run.finished.compareAndSet(false, true)) return@executeOnPooledThread
            val job = run.job
            val stopped = job.stoppedByKiro.get() || job.stoppedByUser.get()
            job.error = reason
            job.summary = when {
                stopped -> "stopped before the tests ran"
                run.buildErrors.isNotEmpty() -> "build failed, ${run.buildErrors.size} error(s)"
                else -> "could not build or start" + (reason?.let { ": $it" } ?: "")
            }
            job.end(if (stopped) JobState.STOPPED else JobState.FAILED, null)
            job.report = TestText.buildFailed(head(job), run.settings.name, run.buildErrors.toList(), job.id)
            jobs.changed()
            if (!job.stoppedByKiro.get()) jobs.ended(job)
        }
    }

    /** Ends the job once the test tree is complete: right away, or (after the process ended) when the tree settles. */
    private fun finishSoon(run: TestRun, waitForTree: Boolean) {
        if (run.finished.get()) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (waitForTree) {
                val until = System.currentTimeMillis() + 5_000
                while (System.currentTimeMillis() < until && (run.root?.isInProgress == true || run.root == null)) Thread.sleep(200)
            }
            finish(run)
        }
    }

    private fun finish(run: TestRun) {
        if (!run.finished.compareAndSet(false, true)) return
        val job = run.job
        val stopped = job.stoppedByKiro.get() || job.stoppedByUser.get()
        val exit = run.handler?.exitCode
        val root = run.root
        try {
            if (root == null) {
                job.summary = if (stopped) "stopped" else "no test results"
                job.end(if (stopped) JobState.STOPPED else JobState.FAILED, exit)
                val tail = job.output.tail(40)
                job.report = head(job) + "\nrun configuration: ${run.settings.name}\nThe run reported no test results (exit code ${exit ?: "?"})." +
                    if (tail.isNotEmpty()) "\nOutput (last lines):\n$tail" else ""
            } else {
                val collected = runReadAction { collect(root) }
                run.failed = collected.failedTests
                val t = collected.tally
                job.summary = TestText.outcome(t, stopped)
                job.end(
                    when {
                        stopped -> JobState.STOPPED
                        t.failed == 0 && t.total > 0 && t.notRun == 0 -> JobState.FINISHED
                        else -> JobState.FAILED
                    },
                    exit,
                )
                var note = job.error
                if (t.total == 0) {
                    val why = root.errorMessage?.takeIf { it.isNotBlank() } ?: job.output.tail(25)
                    note = listOfNotNull(note, why.takeIf { it.isNotBlank() }?.let { "Output:\n$it" }).joinToString("\n").ifEmpty { null }
                }
                job.report = TestText.report(head(job), run.settings.name, t, collected.failures, job.id, note)
            }
        } catch (e: Exception) {
            log.warn("Could not read the test results", e)
            if (job.state.active) job.end(JobState.FAILED, exit)
            job.summary = "results could not be read"
            job.report = head(job) + " (${e.message}). The Run tool window shows them."
        }
        jobs.changed()
        if (!job.stoppedByKiro.get()) jobs.ended(job)
    }

    private fun head(job: Job): String = "Test job #${job.id} (${job.title}) ${job.summary} after ${JobText.duration(job.elapsedMillis())}."

    // ---------- reading the test tree ----------

    private class Collected(val tally: TestTally, val failures: List<TestFailure>, val failedTests: List<SMTestProxy>)

    private fun magnitude(t: SMTestProxy) = t.magnitudeInfo

    private fun failed(t: SMTestProxy) = magnitude(t).let { it == TestStateInfo.Magnitude.FAILED_INDEX || it == TestStateInfo.Magnitude.ERROR_INDEX }

    private fun tally(root: SMTestProxy): TestTally {
        var passed = 0
        var failed = 0
        var ignored = 0
        var notRun = 0
        for (t in root.allTests) {
            if (t.isSuite || t.isConfig) continue
            when (magnitude(t)) {
                TestStateInfo.Magnitude.PASSED_INDEX, TestStateInfo.Magnitude.COMPLETE_INDEX -> passed++
                TestStateInfo.Magnitude.FAILED_INDEX, TestStateInfo.Magnitude.ERROR_INDEX -> failed++
                TestStateInfo.Magnitude.IGNORED_INDEX, TestStateInfo.Magnitude.SKIPPED_INDEX -> ignored++
                else -> notRun++
            }
        }
        return TestTally(passed, failed, ignored, notRun)
    }

    private fun collect(root: SMTestProxy.SMRootTestProxy): Collected {
        val all = root.allTests.filter { it !== root }
        val failedTests = all.filter { !it.isSuite && failed(it) }
        // A suite that failed by itself (set-up, class init) with no failed test under it.
        val failedSuites = all.filter { it.isSuite && failed(it) && (it.errorMessage != null || it.stacktrace != null) && it.children.none(::failed) }
        val scope = GlobalSearchScope.allScope(project)
        val failures = (failedTests + failedSuites).map { t ->
            TestFailure(
                name = nameOf(t, root),
                location = runCatching { t.getLocation(project, scope)?.psiElement?.let(::position) }.getOrNull(),
                message = TestText.message(t.errorMessage, t.stacktrace),
                frames = TestText.frames(t.stacktrace),
            )
        }
        return Collected(tally(root), failures, failedTests + failedSuites)
    }

    private fun nameOf(t: SMTestProxy, root: SMTestProxy): String =
        generateSequence(t) { it.parent as? SMTestProxy }.takeWhile { it !== root }.toList().asReversed().joinToString(" > ") { it.name }

    private fun position(element: PsiElement): String? {
        val vf = element.containingFile?.virtualFile ?: return null
        val doc = FileDocumentManager.getInstance().getDocument(vf)
        val line = doc?.let { it.getLineNumber(element.textOffset.coerceIn(0, it.textLength)) + 1 }
        return relative(vf) + (line?.let { ":$it" } ?: "")
    }

    private fun relative(file: VirtualFile): String = relative(file.path)

    private fun relative(path: String): String {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/')
        val p = path.replace('\\', '/')
        return if (base != null && p.startsWith("$base/")) p.removePrefix("$base/") else p
    }
}
