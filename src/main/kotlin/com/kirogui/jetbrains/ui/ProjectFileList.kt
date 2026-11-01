package com.kirogui.jetbrains.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.fileEditor.impl.EditorHistoryManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.kirogui.jetbrains.kiro.FileEntry
import java.util.concurrent.Callable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The project's files for `@` mentions. Scanned on a background thread and cached, so typing never waits on
 * the disk or the index; the first call returns what is known so far (possibly nothing) and starts a scan.
 */
class ProjectFileList(private val project: Project) {

    @Volatile
    private var cached: List<FileEntry> = emptyList()

    @Volatile
    private var loadedAt = 0L

    /** Increases each time the list is rescanned; lets callers know cached results derived from it may be stale. */
    @Volatile
    var generation = 0
        private set
    private val loading = AtomicBoolean(false)

    fun get(): List<FileEntry> {
        if (System.currentTimeMillis() - loadedAt > REFRESH_MS && loading.compareAndSet(false, true)) {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    cached = ReadAction.nonBlocking(Callable { scan() }).executeSynchronously()
                    loadedAt = System.currentTimeMillis()
                    generation++
                } catch (_: Exception) {
                    // keep the previous list; try again on the next request
                } finally {
                    loading.set(false)
                }
            }
        }
        return cached
    }

    private fun scan(): List<FileEntry> {
        val base = project.basePath?.replace('\\', '/')?.trimEnd('/') ?: return emptyList()
        val types = FileTypeManager.getInstance()
        val recent = EditorHistoryManager.getInstance(project).fileList
            .mapNotNull { rel(it.path, base) }.take(RECENT).toSet()
        val out = ArrayList<FileEntry>()
        ProjectFileIndex.getInstance(project).iterateContent { vf ->
            if (!vf.isDirectory && !types.isFileIgnored(vf) && !vf.fileType.isBinary) {
                rel(vf.path, base)?.let { out.add(FileEntry(it, it in recent)) }
            }
            out.size < MAX_FILES
        }
        return out
    }

    private fun rel(path: String, base: String): String? {
        val p = path.replace('\\', '/')
        return if (p.startsWith("$base/")) p.substring(base.length + 1) else null
    }

    private companion object {
        const val REFRESH_MS = 30_000L
        const val MAX_FILES = 20_000
        const val RECENT = 15
    }
}
