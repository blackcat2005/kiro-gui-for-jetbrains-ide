package com.kirogui.jetbrains.jobs

import com.intellij.ide.structureView.StructureViewTreeElement
import com.intellij.ide.structureView.TreeBasedStructureViewBuilder
import com.intellij.ide.util.gotoByName.GotoClassModel2
import com.intellij.ide.util.gotoByName.GotoSymbolModel2
import com.intellij.lang.LanguageStructureViewBuilder
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.kirogui.jetbrains.service.readNow
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ModuleRootManager
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.PsiNameIdentifierOwner
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.search.searches.DefinitionsScopedSearch
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.indexing.FileBasedIndex
import com.intellij.psi.codeStyle.NameUtil
import com.kirogui.jetbrains.mcp.McpCall
import com.kirogui.jetbrains.mcp.McpResult
import com.kirogui.jetbrains.mcp.McpTool
import com.kirogui.jetbrains.mcp.Schema
import com.kirogui.jetbrains.mcp.argBool
import com.kirogui.jetbrains.mcp.argInt
import com.kirogui.jetbrains.mcp.argString
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Read-only code navigation for Kiro, answered from the IDE's own indexes and PSI instead of grepping files:
 * the open modules, a directory listing, a file's structure, definitions, references, implementations, a call graph,
 * symbol/file/text search and what the user has open. Language-neutral (platform APIs only), so it works wherever the
 * IDE understands the language; quality follows the language plugin.
 */
class CodeTools(private val project: Project, private val parent: com.intellij.openapi.Disposable) {

    fun tools(): List<McpTool> = listOf(
        McpTool(
            "project_modules",
            "The project's root folder and its modules with content roots, source roots, SDK and module dependencies.",
            Schema.obj(),
            ::modules,
        ),
        McpTool(
            "list_dir",
            "List a directory (default: the project root) as a tree. Use it, not shell `ls`/`dir` or reading files one by one, to get to know a project. Skips .git, build output, node_modules and similar unless `all` is true.",
            Schema.obj(
                emptyList(),
                "path" to Schema.string("Directory, absolute or relative to the project root. Default: the project root."),
                "depth" to Schema.integer("Levels to show (default 2, at most 5)."),
                "all" to Schema.boolean("Include hidden and generated folders."),
            ),
            ::listDir,
        ),
        McpTool(
            "file_structure",
            "The outline of a source file as the IDE's Structure view shows it: classes, functions, fields, with line numbers. " +
                "Use it instead of read_file to learn what a file contains; read the body only for the part you need.",
            Schema.obj(listOf("file"), "file" to Schema.string("File path, absolute or relative to the project root.")),
            ::structure,
        ),
        McpTool(
            "find_definition",
            "Where the symbol at a position is declared (use it instead of grep). Give `file` and `line` (1-based) and the symbol's `symbol` name (or `column`).",
            positionSchema(),
            ::definition,
        ),
        McpTool(
            "find_references",
            "All usages of a symbol across the project, exact (use it instead of grep). Point at it with `file` + `line` + `symbol` (or `column`).",
            Schema.obj(
                listOf("file"),
                *positionProps(),
                "limit" to Schema.integer("Most usages to list (default 60, at most 300)."),
            ),
            ::references,
        ),
        McpTool(
            "find_implementations",
            "Implementations of an interface, abstract class or method, and subclasses/overrides. Point at it with `file` + `line` + `symbol`.",
            positionSchema(),
            ::implementations,
        ),
        McpTool(
            "call_graph",
            "Call hierarchy of a function: who calls it (`callers`) or what it calls (`callees`), a few levels deep. " +
                "Point at the function with `file` + `line` + `symbol`.",
            Schema.obj(
                listOf("file"),
                *positionProps(),
                "direction" to Schema.string("callers (default) or callees.", listOf("callers", "callees")),
                "depth" to Schema.integer("Levels (default 2, at most 4)."),
            ),
            ::callGraph,
        ),
        McpTool(
            "find_symbol",
            "Search classes or symbols (functions, fields) by name; camelCase initials, substrings and * wildcards work.",
            Schema.obj(
                listOf("query"),
                "query" to Schema.string("Name or part of it, e.g. UsrSvc or getUser."),
                "kind" to Schema.string("class (default) or symbol (also methods and fields).", listOf("class", "symbol")),
                "limit" to Schema.integer("Most results (default 30, at most 100)."),
            ),
            ::findSymbol,
        ),
        McpTool(
            "find_file",
            "Find project files by name: a substring or a pattern with * and ?.",
            Schema.obj(
                listOf("name"),
                "name" to Schema.string("File name or pattern, e.g. build.gradle*, UserService, *.proto."),
                "limit" to Schema.integer("Most results (default 40, at most 200)."),
            ),
            ::findFile,
        ),
        McpTool(
            "search_text",
            "Search project source text (substring or regex) with file, line and the matching line. Skips binary and very large files.",
            Schema.obj(
                listOf("query"),
                "query" to Schema.string("Text, or a regular expression when `regex` is true."),
                "regex" to Schema.boolean("Treat query as a regular expression."),
                "case_sensitive" to Schema.boolean("Default false."),
                "extension" to Schema.string("Only files with this extension, e.g. kt."),
                "limit" to Schema.integer("Most matches (default 40, at most 200)."),
            ),
            ::searchText,
        ),
        McpTool(
            "editor_state",
            "What the user has open: the selected file with caret line and selection, and the other open editor tabs.",
            Schema.obj(),
            ::editorState,
        ),
    )

    // ---------- project layout ----------

    private fun modules(call: McpCall): McpResult = readNow {
        val base = project.basePath ?: "(unknown)"
        val mods = ModuleManager.getInstance(project).modules.sortedBy { it.name }
        val out = StringBuilder("Project: ${project.name}\nRoot: $base\n${mods.size} module(s)\n")
        for (m in mods) {
            val roots = ModuleRootManager.getInstance(m)
            out.append("\n").append(m.name).append('\n')
            out.append("  content: ").append(roots.contentRoots.joinToString(", ") { rel(it) }.ifEmpty { "-" }).append('\n')
            out.append("  sources: ").append(roots.getSourceRoots(false).joinToString(", ") { rel(it) }.ifEmpty { "-" }).append('\n')
            roots.sdk?.let { out.append("  sdk: ").append(it.name).append('\n') }
            val deps = roots.dependencies.map { it.name }
            if (deps.isNotEmpty()) out.append("  depends on: ").append(deps.joinToString(", ")).append('\n')
        }
        McpResult(out.toString().trimEnd())
    }

    private fun listDir(call: McpCall): McpResult {
        val base = project.basePath ?: return McpResult("The project has no root folder.", isError = true)
        val dir = call.arguments.argString("path")?.let { resolveIo(it, base) } ?: File(base)
        if (!dir.isDirectory) return McpResult("Not a directory: ${dir.path}", isError = true)
        val depth = (call.arguments.argInt("depth") ?: 2).coerceIn(1, 5)
        val all = call.arguments.argBool("all") ?: false
        val out = StringBuilder(dir.path.replace('\\', '/')).append("/\n")
        var count = 0
        fun walk(d: File, level: Int) {
            val entries = (d.listFiles() ?: return).filter { all || !ignoredDir(it) }
                .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
            for (e in entries) {
                if (count >= MAX_ENTRIES) return
                count++
                out.append("  ".repeat(level)).append(e.name).append(if (e.isDirectory) "/" else "").append('\n')
                if (e.isDirectory && level + 1 < depth) walk(e, level + 1)
            }
        }
        walk(dir, 1)
        if (count >= MAX_ENTRIES) out.append("… (stopped at $MAX_ENTRIES entries; narrow `path` or lower `depth`)\n")
        return McpResult(out.toString().trimEnd())
    }

    // ---------- file structure ----------

    private fun structure(call: McpCall): McpResult {
        val path = call.arguments.argString("file") ?: return McpResult("`file` is required.", isError = true)
        return guarded {
            readNow {
                val psi = psiFile(path) ?: return@readNow McpResult("No such file: $path", isError = true)
                val builder = LanguageStructureViewBuilder.getInstance().getStructureViewBuilder(psi) as? TreeBasedStructureViewBuilder
                    ?: return@readNow McpResult("The IDE has no structure view for ${psi.name}.", isError = true)
                val model = builder.createStructureViewModel(null)
                try {
                    val out = StringBuilder(rel(psi.virtualFile)).append('\n')
                    var count = 0
                    fun walk(node: StructureViewTreeElement, level: Int) {
                        for (child in node.children) {
                            if (count++ >= MAX_NODES) return
                            val element = child as? StructureViewTreeElement ?: continue
                            val text = element.presentation.presentableText ?: continue
                            val line = (element.value as? PsiElement)?.let { lineOf(it) }
                            out.append("  ".repeat(level)).append(text).append(line?.let { "  :$it" } ?: "").append('\n')
                            walk(element, level + 1)
                        }
                    }
                    walk(model.root, 1)
                    McpResult(out.toString().trimEnd())
                } finally {
                    Disposer.dispose(model)
                }
            }
        }
    }

    // ---------- symbols ----------

    private fun definition(call: McpCall): McpResult = withTarget(call) { target ->
        McpResult("Declared at ${where(target)}\n${snippet(target)}")
    }

    private fun references(call: McpCall): McpResult = withTarget(call) { target ->
        val limit = (call.arguments.argInt("limit") ?: 60).coerceIn(1, 300)
        val found = ReferencesSearch.search(target).findAll()
        val out = StringBuilder("${nameOf(target)} (${where(target)}): ${found.size} usage(s)\n")
        for (ref in found.take(limit)) out.append("  ").append(where(ref.element)).append("  ").append(lineText(ref.element)).append('\n')
        if (found.size > limit) out.append("… ${found.size - limit} more\n")
        McpResult(out.toString().trimEnd())
    }

    private fun implementations(call: McpCall): McpResult = withTarget(call) { target ->
        val found = DefinitionsScopedSearch.search(target).findAll().filter { it !== target }
        if (found.isEmpty()) return@withTarget McpResult("No implementations or overrides found for ${nameOf(target)}.")
        McpResult(
            "${nameOf(target)} has ${found.size}:\n" +
                found.take(100).joinToString("\n") { "  ${nameOf(it)}  ${where(it)}" } + if (found.size > 100) "\n… ${found.size - 100} more" else "",
        )
    }

    private fun callGraph(call: McpCall): McpResult = withTarget(call) { start ->
        val callers = call.arguments.argString("direction")?.lowercase() != "callees"
        val depth = (call.arguments.argInt("depth") ?: 2).coerceIn(1, 4)
        val out = StringBuilder("${if (callers) "Callers of" else "Callees of"} ${nameOf(start)} (${where(start)})\n")
        val seen = HashSet<PsiElement>()
        var count = 0
        fun walk(element: PsiElement, level: Int) {
            if (level > depth) return
            val next = if (callers) callersOf(element) else calleesOf(element)
            for (n in next) {
                if (count >= MAX_NODES) return
                val again = !seen.add(n)
                count++
                out.append("  ".repeat(level)).append(nameOf(n)).append("  ").append(where(n)).append(if (again) "  (see above)" else "").append('\n')
                if (!again) walk(n, level + 1)
            }
        }
        seen.add(start)
        walk(start, 1)
        if (count == 0) out.append("  (none found)\n")
        if (count >= MAX_NODES) out.append("… (stopped at $MAX_NODES entries; lower `depth`)\n")
        McpResult(out.toString().trimEnd())
    }

    /** The functions whose bodies use [target]: each reference's nearest enclosing function-like element. */
    private fun callersOf(target: PsiElement): List<PsiElement> =
        ReferencesSearch.search(target).findAll().mapNotNull { enclosingCallable(it.element) }.filter { it !== target }.distinct()

    /** The project functions [target] uses: what its references resolve to. */
    private fun calleesOf(target: PsiElement): List<PsiElement> {
        val index = ProjectFileIndex.getInstance(project)
        val found = LinkedHashSet<PsiElement>()
        PsiTreeUtil.processElements(target) { e ->
            val resolved = e.reference?.resolve()
            if (resolved != null && resolved !== target && isCallable(resolved) &&
                resolved.containingFile?.virtualFile?.let { index.isInContent(it) } == true
            ) found.add(resolved)
            found.size < MAX_NODES
        }
        return found.toList()
    }

    private fun findSymbol(call: McpCall): McpResult {
        val query = call.arguments.argString("query")?.trim() ?: return McpResult("`query` is required.", isError = true)
        val limit = (call.arguments.argInt("limit") ?: 30).coerceIn(1, 100)
        val symbols = call.arguments.argString("kind")?.lowercase() == "symbol"
        return guarded {
            readNow {
                val matcher = NameUtil.buildMatcher("*$query").build()
                val model = if (symbols) GotoSymbolModel2(project, parent) else GotoClassModel2(project)
                @Suppress("DEPRECATION")
                val names = model.getNames(false).filter { matcher.matches(it) }.sortedBy { it.length }
                val out = ArrayList<String>()
                for (name in names) {
                    if (out.size >= limit) break
                    for (item in model.getElementsByName(name, false, name)) {
                        val element = item as? PsiElement ?: continue
                        out.add("${nameOf(element)}  ${where(element)}")
                        if (out.size >= limit) break
                    }
                }
                McpResult(if (out.isEmpty()) "No ${if (symbols) "symbol" else "class"} matches \"$query\"." else out.joinToString("\n"))
            }
        }
    }

    private fun findFile(call: McpCall): McpResult {
        val pattern = call.arguments.argString("name")?.trim() ?: return McpResult("`name` is required.", isError = true)
        val limit = (call.arguments.argInt("limit") ?: 40).coerceIn(1, 200)
        val matches = namePattern(pattern)
        val out = ArrayList<String>()
        readNow {
            ProjectFileIndex.getInstance(project).iterateContent { f ->
                if (!f.isDirectory && matches(f.name)) out.add(rel(f))
                out.size < limit
            }
        }
        return McpResult(if (out.isEmpty()) "No file matches \"$pattern\"." else out.sorted().joinToString("\n"))
    }

    private fun searchText(call: McpCall): McpResult {
        val query = call.arguments.argString("query") ?: return McpResult("`query` is required.", isError = true)
        val limit = (call.arguments.argInt("limit") ?: 40).coerceIn(1, 200)
        val extension = call.arguments.argString("extension")?.removePrefix(".")?.lowercase()
        val sensitive = call.arguments.argBool("case_sensitive") ?: false
        val regex = try {
            Regex(if (call.arguments.argBool("regex") == true) query else Regex.escape(query), if (sensitive) emptySet() else setOf(RegexOption.IGNORE_CASE))
        } catch (e: Exception) {
            return McpResult("Bad regular expression: ${e.message}", isError = true)
        }
        val out = ArrayList<String>()
        val deadline = System.currentTimeMillis() + SEARCH_BUDGET_MS
        var cut = false
        readNow {
            ProjectFileIndex.getInstance(project).iterateContent { f ->
                if (System.currentTimeMillis() > deadline) {
                    cut = true
                    return@iterateContent false
                }
                if (f.isDirectory || f.fileType.isBinary || f.length > MAX_FILE_BYTES) return@iterateContent true
                if (extension != null && f.extension?.lowercase() != extension) return@iterateContent true
                val text = try {
                    String(f.contentsToByteArray(), f.charset)
                } catch (_: Exception) {
                    return@iterateContent true
                }
                var n = 0
                for (line in text.lineSequence()) {
                    n++
                    if (regex.containsMatchIn(line)) {
                        out.add("${rel(f)}:$n  ${line.trim().take(200)}")
                        if (out.size >= limit) return@iterateContent false
                    }
                }
                true
            }
        }
        val note = if (cut) "\n… stopped after ${SEARCH_BUDGET_MS / 1000}s; narrow with `extension`" else ""
        return McpResult(if (out.isEmpty()) "No match for \"$query\".$note" else out.joinToString("\n") + note)
    }

    private fun editorState(call: McpCall): McpResult {
        val future = CompletableFuture<String>()
        ApplicationManager.getApplication().invokeLater({
            try {
                val manager = FileEditorManager.getInstance(project)
                val editor = manager.selectedTextEditor
                val selected = manager.selectedFiles.firstOrNull()
                val out = StringBuilder()
                if (selected == null) {
                    out.append("No file is open in the editor.")
                } else {
                    out.append("Selected: ").append(rel(selected))
                    editor?.let {
                        val caret = it.caretModel.logicalPosition
                        out.append("\nCaret: line ${caret.line + 1}, column ${caret.column + 1}")
                        it.selectionModel.selectedText?.takeIf { s -> s.isNotBlank() }?.let { s ->
                            out.append("\nSelection (${s.length} chars):\n").append(s.take(1500)).append(if (s.length > 1500) "\n…" else "")
                        }
                    }
                    val others = manager.openFiles.filter { it != selected }
                    if (others.isNotEmpty()) out.append("\nAlso open: ").append(others.joinToString(", ") { rel(it) })
                }
                future.complete(out.toString())
            } catch (e: Exception) {
                future.completeExceptionally(e)
            }
        }, ModalityState.any())
        return McpResult(future.get(10, TimeUnit.SECONDS))
    }

    // ---------- helpers ----------

    private fun positionSchema() = Schema.obj(listOf("file"), *positionProps())

    private fun positionProps(): Array<Pair<String, kotlinx.serialization.json.JsonObject>> = arrayOf(
        "file" to Schema.string("File path, absolute or relative to the project root."),
        "line" to Schema.integer("Line (1-based) where the symbol is declared or used."),
        "symbol" to Schema.string("The symbol's name on that line (or, with no line, its declaration anywhere in the file)."),
        "column" to Schema.integer("Column (1-based); only needed when the name is ambiguous."),
    )

    private fun <T> guarded(block: () -> T): T where T : Any = block()

    /** Runs [body] in a read action on the symbol the arguments point at, or explains why there is none. */
    private fun withTarget(call: McpCall, body: (PsiElement) -> McpResult): McpResult {
        if (DumbService.getInstance(project).isDumb) return McpResult("The IDE is still indexing; try again in a moment.", isError = true)
        return readNow {
            val path = call.arguments.argString("file") ?: return@readNow McpResult("`file` is required.", isError = true)
            val psi = psiFile(path) ?: return@readNow McpResult("No such file: $path", isError = true)
            val target = targetAt(psi, call.arguments.argInt("line"), call.arguments.argInt("column"), call.arguments.argString("symbol"))
                ?: return@readNow McpResult(
                    "No symbol found there. Give `line` and the `symbol` name exactly as written, or its `column`.",
                    isError = true,
                )
            body(target)
        }
    }

    private fun targetAt(psi: com.intellij.psi.PsiFile, line: Int?, column: Int?, symbol: String?): PsiElement? {
        val doc = PsiDocumentManager.getInstance(project).getDocument(psi) ?: return null
        if (symbol != null) {
            val named = PsiTreeUtil.findChildrenOfType(psi, PsiNamedElement::class.java).filter { it.name == symbol }
            val onLine = line?.let { l -> named.firstOrNull { lineOf(it) == l } }
            (onLine ?: if (line == null) named.firstOrNull() else null)?.let { return it }
        }
        var offset = when {
            line == null -> return null
            line < 1 || line > doc.lineCount -> return null
            else -> doc.getLineStartOffset(line - 1) + ((column ?: 1) - 1).coerceAtLeast(0)
        }
        if (symbol != null) {
            val start = doc.getLineStartOffset(line - 1)
            val index = doc.getText(com.intellij.openapi.util.TextRange(start, doc.getLineEndOffset(line - 1))).indexOf(symbol)
            if (index >= 0) offset = start + index
        } else if (column == null) {
            val start = doc.getLineStartOffset(line - 1)
            val text = doc.getText(com.intellij.openapi.util.TextRange(start, doc.getLineEndOffset(line - 1)))
            offset = start + text.indexOfFirst { !it.isWhitespace() }.coerceAtLeast(0)
        }
        psi.findReferenceAt(offset)?.resolve()?.let { return it }
        var e: PsiElement? = psi.findElementAt(offset)
        while (e != null && e !is com.intellij.psi.PsiFile) {
            if (e is PsiNameIdentifierOwner && e.nameIdentifier?.textRange?.containsOffset(offset) == true) return e
            e = e.parent
        }
        return null
    }

    private fun isCallable(e: PsiElement): Boolean {
        if (e !is PsiNamedElement) return false
        val kind = e.javaClass.simpleName
        return kind.contains("Function") || kind.contains("Method") || kind.contains("Constructor")
    }

    private fun enclosingCallable(e: PsiElement): PsiElement? {
        var p: PsiElement? = e
        while (p != null && p !is com.intellij.psi.PsiFile) {
            if (isCallable(p)) return p
            p = p.parent
        }
        return null
    }

    private fun psiFile(path: String): com.intellij.psi.PsiFile? {
        val vf = resolveVirtual(path) ?: return null
        return PsiManager.getInstance(project).findFile(vf)
    }

    private fun resolveVirtual(path: String): VirtualFile? {
        val base = project.basePath
        val file = File(path).let { if (it.isAbsolute || base == null) it else File(base, path) }
        return LocalFileSystem.getInstance().refreshAndFindFileByPath(file.path.replace('\\', '/'))
    }

    private fun resolveIo(path: String, base: String): File = File(path).let { if (it.isAbsolute) it else File(base, path) }

    private fun rel(f: VirtualFile): String {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/')
        return if (base != null && f.path.startsWith("$base/")) f.path.removePrefix("$base/") else if (f.path == base) "." else f.path
    }

    private fun lineOf(e: PsiElement): Int? {
        val file = e.containingFile ?: return null
        val doc = PsiDocumentManager.getInstance(project).getDocument(file) ?: return null
        val offset = (e as? PsiNameIdentifierOwner)?.nameIdentifier?.textOffset ?: e.textOffset
        return if (offset in 0..doc.textLength) doc.getLineNumber(offset) + 1 else null
    }

    private fun where(e: PsiElement): String {
        val vf = e.containingFile?.virtualFile ?: return "(no file)"
        return "${rel(vf)}:${lineOf(e) ?: "?"}"
    }

    private fun nameOf(e: PsiElement): String = (e as? PsiNamedElement)?.name ?: e.text.take(40)

    private fun lineText(e: PsiElement): String {
        val doc = e.containingFile?.let { PsiDocumentManager.getInstance(project).getDocument(it) } ?: return ""
        val line = lineOf(e)?.minus(1) ?: return ""
        return doc.getText(com.intellij.openapi.util.TextRange(doc.getLineStartOffset(line), doc.getLineEndOffset(line))).trim().take(160)
    }

    private fun snippet(e: PsiElement): String = e.text.lineSequence().take(8).joinToString("\n").take(600)

    companion object {
        const val MAX_ENTRIES = 300
        const val MAX_NODES = 150
        const val MAX_FILE_BYTES = 1_000_000L
        const val SEARCH_BUDGET_MS = 8_000L

        private val IGNORED_DIRS = setOf(
            ".git", ".idea", ".gradle", ".kotlin", ".svn", ".hg", "node_modules", "build", "out", "target", "dist", "__pycache__",
            ".venv", "venv", ".mypy_cache", ".pytest_cache", ".next", ".cache",
        )

        /** A folder the default listing hides: VCS metadata, IDE state, build output, dependency caches. */
        fun ignoredDir(f: File): Boolean = f.isDirectory && f.name in IGNORED_DIRS

        /** A name filter: `*` and `?` make a pattern, anything else is a case-insensitive substring. */
        fun namePattern(pattern: String): (String) -> Boolean {
            if (pattern.none { it == '*' || it == '?' }) return { it.contains(pattern, ignoreCase = true) }
            val regex = Regex(pattern.map { when (it) { '*' -> ".*"; '?' -> "."; else -> Regex.escape(it.toString()) } }.joinToString(""), RegexOption.IGNORE_CASE)
            return { regex.matches(it) }
        }
    }
}
