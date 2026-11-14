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

/** The children of a directory, [ticket] telling how recent they are. See [FileTreeState]. */
@Immutable
class TreeListing(val entries: List<FileEntry>, val ticket: Long)

/** What revealing a file expands: each directory down to it, with its children. See [FileTreeState.listingsTo]. */
@Immutable
class TreeReveal(val path: String, val levels: List<Pair<FileEntry, TreeListing>>)

@Immutable
data class TreeRow(val entry: FileEntry, val depth: Int, val expanded: Boolean, val loading: Boolean) {
    /** What the row shows: the name of [entry], or `src/main/java` when the server folded a chain of directories. */
    val label: String get() = entry.label ?: entry.name
}

/**
 * Project tree with lazy loading: a directory is listed only the first time it is expanded, and only the
 * visible (expanded) part of the tree is flattened into [rows], which feeds a virtualized list.
 *
 * Listings are requested concurrently and answer in any order: each request takes a ticket, and a directory only takes
 * a listing at least as recent as the one it holds (see [store]), so that the tree never goes back to an older state.
 */
@Stable
@Suppress("TooManyFunctions")
class FileTreeState(val root: FileEntry, private val fileSystem: ProjectFileSystem, private val scope: CoroutineScope) {
    private val children = mutableStateMapOf<String, List<FileEntry>>()
    private val expanded = mutableStateMapOf<String, Boolean>()
    private val loading = mutableStateMapOf<String, Boolean>()
    private val directories = HashMap<String, FileEntry>()
    private val heldTickets = HashMap<String, Long>()

    // The directory listing each folded entry (`src/main/java`), whose parent path is not a directory of the tree.
    private val listedIn = HashMap<String, FileEntry>()
    private var lastTicket = 0L

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
            if (directory != null && expanded[path] == true) load(directory) else forget(path)
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
        return directories[parentPath] ?: listedIn[entry.path] ?: root.takeIf { parentPath == it.path }
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
        val levels = ArrayList<Pair<FileEntry, TreeListing>>()
        for ((directory, listing) in chain.zip(listed)) {
            val parentChildren = levels.lastOrNull()?.second?.entries
            // The entry the server listed, rather than the one built from the path (whose separators may be mixed).
            val listedEntry = parentChildren?.firstOrNull { slashed(it.path) == slashed(directory.path) }
            // A directory inside a folded entry has no row of its own: its listing is not needed.
            val insideFold = listedEntry == null && parentChildren.orEmpty().any { it.folds(directory) }
            val entry = if (parentChildren == null) directory else listedEntry
            if (!insideFold) {
                if (listing == null || entry == null) break
                levels += entry to listing
            }
        }
        return TreeReveal(path, levels)
    }

    /** Expands the directories of [reveal] and selects its file in the same frame: the tree never unfolds by levels. */
    fun show(reveal: TreeReveal) {
        Snapshot.withMutableSnapshot {
            for ((directory, listing) in reveal.levels) {
                directories[directory.path] = directory
                store(directory, listing)
                // Listed again if a refresh forgot it since: its listing above was refused.
                expand(directory)
            }
            selectedPath = reveal.path
        }
    }

    private suspend fun listingOf(directory: FileEntry): TreeListing? {
        val cached = children[directory.path]
        if (cached != null) return TreeListing(cached, heldTickets[directory.path] ?: 0L)
        val ticket = nextTicket()
        val listed = runCatching { fileSystem.listFolded(directory) }.getOrNull()
        return listed?.let { TreeListing(it, ticket) }
    }

    fun select(entry: FileEntry) {
        selectedPath = entry.path
    }

    private fun load(directory: FileEntry) {
        directories[directory.path] = directory
        loading[directory.path] = true
        val ticket = nextTicket()
        scope.launch {
            // From the click to the frame that shows the children.
            ClientTelemetry.measureToFrame(
                Operation.TreeExpand,
                outcomeOf = { listed: Result<List<FileEntry>> -> listed.outcome() },
            ) {
                val listed = runCatching { fileSystem.listFolded(directory) }
                store(directory, TreeListing(listed.getOrElse { emptyList() }, ticket))
                listed
            }
        }
    }

    private fun nextTicket(): Long {
        lastTicket += 1
        return lastTicket
    }

    // The only way into [children]: a listing older than the one held is refused, whatever the order of the answers.
    private fun store(directory: FileEntry, listing: TreeListing) {
        val held = heldTickets[directory.path] ?: 0L
        if (listing.ticket < held) return
        heldTickets[directory.path] = listing.ticket
        children[directory.path] = listing.entries
        listing.entries.filter { it.label != null }.forEach { listedIn[it.path] = directory }
        loading.remove(directory.path)
    }

    // Drops the listing of [path], and refuses the answers requested before: it is listed again when next expanded.
    private fun forget(path: String) {
        heldTickets[path] = nextTicket()
        children.remove(path)
        loading.remove(path)
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

// This entry is a folded chain of directories going through [directory].
private fun FileEntry.folds(directory: FileEntry): Boolean {
    val inner = slashed(directory.path).trimEnd('/') + "/"
    return label != null && slashed(path).startsWith(inner)
}
