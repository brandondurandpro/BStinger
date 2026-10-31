package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem

@Immutable
data class TreeRow(val entry: FileEntry, val depth: Int, val expanded: Boolean, val loading: Boolean)

/**
 * Project tree with lazy loading: a directory is listed only the first time it is expanded, and only the
 * visible (expanded) part of the tree is flattened into [rows], which feeds a virtualized list.
 */
@Stable
class FileTreeState(val root: FileEntry, private val fileSystem: ProjectFileSystem, private val scope: CoroutineScope) {
    private val children = mutableStateMapOf<String, List<FileEntry>>()
    private val expanded = mutableStateMapOf<String, Boolean>()
    private val loading = mutableStateMapOf<String, Boolean>()
    private val directories = HashMap<String, FileEntry>()

    var selectedPath: String? by mutableStateOf(root.path)

    /** Vertical scroll of the tree, in rows; kept while the project view is hidden. */
    val scroll = VerticalScroll()

    val rows: List<TreeRow> by derivedStateOf { buildRows() }

    init {
        expand(root)
    }

    fun isExpanded(entry: FileEntry): Boolean = expanded[entry.path] == true

    fun expand(entry: FileEntry) {
        if (!entry.isDirectory) return
        expanded[entry.path] = true
        if (entry.path !in children && loading[entry.path] != true) load(entry)
    }

    fun collapse(entry: FileEntry) {
        expanded.remove(entry.path)
    }

    fun toggle(entry: FileEntry) {
        if (isExpanded(entry)) collapse(entry) else expand(entry)
    }

    /** Re-lists every loaded directory (e.g. after files were created on disk). */
    fun refresh() {
        children.keys.toList().forEach { path ->
            val directory = directories[path]
            if (directory != null && expanded[path] == true) load(directory) else children.remove(path)
        }
    }

    /** Re-lists the directory at [path] if it was already loaded (after a file was created, renamed or deleted). */
    fun reload(path: String) {
        val directory = directories[path] ?: return
        if (path in children) load(directory)
    }

    /** The loaded parent directory of [entry], `null` for the root. */
    fun parentOf(entry: FileEntry): FileEntry? {
        if (entry.path == root.path) return null
        val parentPath = entry.path.substring(0, entry.path.lastIndexOfAny(charArrayOf('/', '\\')).coerceAtLeast(0))
        return directories[parentPath] ?: root.takeIf { parentPath == it.path }
    }

    /** Expands every ancestor of [path] and selects it, loading directories as needed. */
    fun reveal(path: String) {
        scope.launch {
            var current: FileEntry? = root
            while (current != null && current.path != path && path.startsWith(current.path)) {
                val directory: FileEntry = current
                expand(directory)
                directories[directory.path] = directory
                val list =
                    children[directory.path]
                        ?: runCatching {
                            fileSystem.list(directory)
                        }.getOrNull()?.also { children[directory.path] = it }
                current = list?.firstOrNull { isSelfOrAncestor(it.path, path) }
            }
            selectedPath = path
        }
    }

    fun select(entry: FileEntry) {
        selectedPath = entry.path
    }

    private fun load(directory: FileEntry) {
        directories[directory.path] = directory
        loading[directory.path] = true
        scope.launch {
            val list = runCatching { fileSystem.list(directory) }.getOrElse { emptyList() }
            children[directory.path] = list
            loading.remove(directory.path)
        }
    }

    private fun buildRows(): List<TreeRow> {
        val out = ArrayList<TreeRow>()

        fun visit(entry: FileEntry, depth: Int) {
            val isExpanded = expanded[entry.path] == true
            out += TreeRow(entry, depth, isExpanded, loading[entry.path] == true)
            if (isExpanded) children[entry.path]?.forEach { visit(it, depth + 1) }
        }
        visit(root, 0)
        return out
    }
}

private fun isSelfOrAncestor(candidate: String, path: String): Boolean =
    path == candidate || path.startsWith("$candidate/") || path.startsWith("$candidate\\")
