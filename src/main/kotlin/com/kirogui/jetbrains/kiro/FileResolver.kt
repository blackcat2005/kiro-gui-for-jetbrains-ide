package com.kirogui.jetbrains.kiro

/** Decides whether a piece of text from an answer names a real file; returns a stable target, or null. */
fun interface FileLinker {
    fun resolve(raw: String): String?
}

/**
 * Resolves the file names Kiro writes in its answers ("src/Foo.kt", "Foo.kt", "D:\proj\Foo.kt") against the
 * project. Returns a project-relative path with '/' separators, or an absolute path for files outside the
 * project; null when nothing matches or a bare name is ambiguous.
 *
 * @param files all project files (relative paths); may return the same list instance until it is rescanned.
 * @param base the project directory, '/' separators, no trailing slash.
 * @param absoluteExists whether an absolute path exists on disk (for files outside the scanned list).
 */
class FileResolver(
    private val files: () -> List<FileEntry>,
    private val base: String?,
    private val absoluteExists: (String) -> Boolean,
) : FileLinker {

    private var indexedFrom: List<FileEntry>? = null
    private var byLowerPath = emptyMap<String, String>()
    private var byLowerName = emptyMap<String, List<FileEntry>>()

    override fun resolve(raw: String): String? {
        val p = raw.replace('\\', '/').trim().removePrefix("./")
        if (p.isEmpty()) return null
        index()

        if (isAbsolute(p)) {
            val b = base
            if (b != null && p.startsWith("$b/", ignoreCase = true)) {
                val rel = p.substring(b.length + 1)
                return byLowerPath[rel.lowercase()] ?: rel.takeIf { absoluteExists(p) }
            }
            return p.takeIf(absoluteExists)
        }

        byLowerPath[p.lowercase()]?.let { return it }
        return if ('/' in p) {
            // "main/Foo.kt" for "src/main/Foo.kt": a path ending the same way, shortest (closest to the root) first
            byLowerName[p.substringAfterLast('/').lowercase()]
                ?.filter { it.path.endsWith("/$p", ignoreCase = true) }
                ?.minByOrNull { it.path.length }?.path
        } else {
            val same = byLowerName[p.lowercase()].orEmpty()
            when {
                same.size == 1 -> same[0].path
                else -> same.singleOrNull { it.recent }?.path // several: only if exactly one was opened recently
            }
        }
    }

    private fun index() {
        val list = files()
        if (list === indexedFrom) return
        indexedFrom = list
        byLowerPath = list.associate { it.path.lowercase() to it.path }
        byLowerName = list.groupBy { it.path.substringAfterLast('/').lowercase() }
    }

    companion object {
        fun isAbsolute(p: String) = p.startsWith("/") || (p.length > 2 && p[1] == ':' && (p[2] == '/' || p[2] == '\\'))
    }
}
