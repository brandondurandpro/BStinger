package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileEntry
import java.util.TreeSet

@Immutable
data class DirectoryMatch(val relativePath: String, val score: Int, val matchedIndices: List<Int>)

private val BEST_DIRECTORY_FIRST =
    compareByDescending<DirectoryMatch> { it.score }
        .thenBy { it.relativePath.substringAfterLast('/').length }
        .thenBy { it.relativePath }

/** The directories of the files of an index (`src`, `src/main`…), derived once per list, matched by name. */
class DirectoryIndex {
    // The directories of a list of files, by their path relative to the root, cached per list (off the UI thread).
    @Volatile
    private var directories: Pair<List<FileEntry>, List<String>>? = null

    fun invalidate() {
        directories = null
    }

    /**
     * The directories holding the files of [all] (`src`, `src/main`…) whose name matches [query], the best matches
     * first; all of them, in the order of their paths, for an empty query. An empty directory, which holds no file, is
     * not listed.
     */
    suspend fun search(
        all: List<FileEntry>,
        relativePath: (FileEntry) -> String,
        query: String,
        limit: Int,
    ): List<DirectoryMatch> {
        val q = query.trim()
        return withContext(Dispatchers.Default) {
            val paths = pathsOf(all, relativePath)
            val matches =
                if (q.isEmpty()) {
                    paths.take(limit).map { DirectoryMatch(it, 0, emptyList()) }
                } else {
                    paths.mapNotNull { path -> match(q, path) }
                }
            matches.sortedWith(BEST_DIRECTORY_FIRST).take(limit)
        }
    }

    private fun match(query: String, path: String): DirectoryMatch? {
        val nameStart = path.lastIndexOf('/') + 1
        val matched = FileIndex.fuzzyMatch(query, path.substring(nameStart)) ?: return null
        val (score, indices) = matched
        return DirectoryMatch(path, score, indices.map { it + nameStart })
    }

    private fun pathsOf(all: List<FileEntry>, relativePath: (FileEntry) -> String): List<String> {
        val cached = directories
        if (cached != null && cached.first === all) return cached.second
        val found = TreeSet<String>()
        for (entry in all) {
            var directory = relativePath(entry).substringBeforeLast('/', "")
            // The parents of a directory already found are found too.
            while (directory.isNotEmpty() && found.add(directory)) directory = directory.substringBeforeLast('/', "")
        }
        val paths = found.toList()
        directories = all to paths
        return paths
    }
}
