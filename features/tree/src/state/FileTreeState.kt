package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.model.slashed
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame
import org.example.kotlineditor.telemetry.outcome

/** What revealing a file expands: each directory down to it, with its children. See [FileTreeState.listingsTo]. */
@Immutable
class TreeReveal(val path: String, val levels: List<Pair<FileEntry, List<FileEntry>>>)

@Immutable
data class TreeRow(val entry: FileEntry, val depth: Int, val expanded: Boolean, val loading: Boolean)

/**
 * Project tree with lazy loading: a directory is listed only the first time it is expanded, and only the
 * visible (expanded) part of the tree is flattened into [rows], which feeds a virtualized list.
 */
@Stable
@Suppress("TooManyFunctions")
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
        scope.launch { show(listingsTo(path)) }
    }

    /**
     * The directories from the root down to the parent of [path], each with its children, all listed at once: one
     * round trip, however deep the file is. A directory missing on the way ends the list.
     */
    suspend fun listingsTo(path: String): TreeReveal {
        val chain = ancestorsOf(root, path)
        val listed =
            coroutineScope {
                chain.map { directory -> async { listingOf(directory) } }.awaitAll()
            }
        val levels = ArrayList<Pair<FileEntry, List<FileEntry>>>()
        for ((directory, list) in chain.zip(listed)) {
            val parentChildren = levels.lastOrNull()?.second
            // The entry the server listed, rather than the one built from the path (whose separators may be mixed).
            val listedEntry = parentChildren?.firstOrNull { slashed(it.path) == slashed(directory.path) }
            val entry = if (parentChildren == null) directory else listedEntry
            if (list == null || entry == null) break
            levels += entry to list
        }
        return TreeReveal(path, levels)
    }

    /** Expands the directories of [reveal] and selects its file in the same frame: the tree never unfolds by levels. */
    fun show(reveal: TreeReveal) {
        Snapshot.withMutableSnapshot {
            for ((directory, list) in reveal.levels) {
                directories[directory.path] = directory
                // A listing in place is at least as recent: this one may predate a reload that answered since.
                if (directory.path !in children) {
                    children[directory.path] = list
                    loading.remove(directory.path)
                }
                expanded[directory.path] = true
            }
            selectedPath = reveal.path
        }
    }

    private suspend fun listingOf(directory: FileEntry): List<FileEntry>? =
        children[directory.path] ?: runCatching { fileSystem.list(directory) }.getOrNull()

    fun select(entry: FileEntry) {
        selectedPath = entry.path
    }

    private fun load(directory: FileEntry) {
        directories[directory.path] = directory
        loading[directory.path] = true
        scope.launch {
            // From the click to the frame that shows the children.
            ClientTelemetry.measureToFrame(
                Operation.TreeExpand,
                outcomeOf = { listed: Result<List<FileEntry>> -> listed.outcome() },
            ) {
                val listed = runCatching { fileSystem.list(directory) }
                children[directory.path] = listed.getOrElse { emptyList() }
                loading.remove(directory.path)
                listed
            }
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

private val SEPARATORS = setOf('/', '\\')

// [root], then each directory of [path] below it, built from the path itself so that they are listed together.
private fun ancestorsOf(root: FileEntry, path: String): List<FileEntry> {
    if (!root.leadsTo(path)) return emptyList()
    val separators = SEPARATORS.toCharArray()
    val chain = arrayListOf(root)
    var end = path.indexOfAny(separators, root.path.length + 1)
    while (end >= 0) {
        val directory = path.substring(0, end)
        val name = directory.substring(directory.lastIndexOfAny(separators) + 1)
        chain += FileEntry(directory, name, isDirectory = true)
        end = path.indexOfAny(separators, end + 1)
    }
    return chain
}

// [target] is under this directory, deeper than it.
private fun FileEntry.leadsTo(target: String): Boolean {
    val endsWithSeparator = path.lastOrNull() in SEPARATORS
    val nextIsSeparator = target.getOrNull(path.length) in SEPARATORS
    val below = endsWithSeparator || nextIsSeparator
    return target.length > path.length && target.startsWith(path) && below
}
