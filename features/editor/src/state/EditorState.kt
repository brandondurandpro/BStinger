package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.MergeSides
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import java.io.IOException

private const val MAX_RECENT_FILES = 50

// A review belongs to no file: its tab is told apart by its mode, this entry only gives it a name.
private val REVIEW_ENTRY = FileEntry(path = "review", name = "Review", isDirectory = false)

/**
 * Open editors: one pane, or two after "Split Right". Every action applies to the active pane; a file open in
 * both panes shares its [Document]. Closing the active tab activates the most recently used one, like IntelliJ.
 */
@Stable
@Suppress("TooManyFunctions")
class EditorState(
    fileSystem: ProjectFileSystem,
    private val scope: CoroutineScope,
    private val relativePath: (FileEntry) -> String = { it.name },
    onSaved: (FileEntry) -> Unit = {},
    diffs: suspend (FileEntry) -> List<DiffLine> = { emptyList() },
    headLines: suspend (FileEntry) -> List<String>? = { null },
    blames: suspend (FileEntry) -> List<BlameLine> = { emptyList() },
    commitDiffs: suspend (FileEntry, CommitDiffTarget) -> List<DiffLine> = { _, _ -> emptyList() },
    merges: suspend (FileEntry) -> MergeSides = { throw IOException("No merge available") },
    grammars: GrammarStore? = null,
) {
    private val files =
        TabFiles(fileSystem, scope, onSaved, diffs, headLines, blames, commitDiffs, merges, grammars)

    val panes = mutableStateListOf(EditorPane())

    var activePane: EditorPane by mutableStateOf(panes.first())
        internal set

    /** Tabs of the active pane. */
    val tabs: List<EditorTab> get() = activePane.tabs

    val activeTab: EditorTab? get() = activePane.activeTab

    val allTabs: List<EditorTab> get() = panes.flatMap { it.tabs }

    val isSplit: Boolean get() = panes.size > 1

    /** Most recently activated files first (Ctrl+E). */
    val recentFiles = mutableStateListOf<FileEntry>()

    private var activationCounter = 0L

    fun open(entry: FileEntry): EditorTab = openIn(activePane, entry)

    /** Opens (or activates) the diff tab of [entry]. */
    fun openDiff(entry: FileEntry): EditorTab = openIn(activePane, entry, diff = true)

    /** Opens what [commit] changed in [entry] (a file of a commit in the Branches window). */
    fun openCommitDiff(entry: FileEntry, commit: CommitDiffTarget): EditorTab =
        openIn(activePane, entry, diff = true, commit = commit)

    /** Opens (or activates) the merge tab of the conflicted [entry]: its three versions side by side. */
    fun openMerge(entry: FileEntry): EditorTab = openIn(activePane, entry, diff = true, merge = true)

    /**
     * Opens (or activates) the review tab of [files], whose paths are relative to the project root. Opened again, the
     * tab keeps the verdicts of the files that are still there.
     */
    fun openReview(files: List<ReviewFile>, mode: ReviewMode = ReviewMode.Manual): EditorTab {
        val tab = openIn(activePane, REVIEW_ENTRY, diff = true, review = mode)
        val current = (tab.status as? TabStatus.Review)?.state
        val state = current ?: ReviewState(mode).also { tab.status = TabStatus.Review(it) }
        state.update(files)
        return tab
    }

    /** The changes of [entry] against HEAD (what a diff tab shows, and the review next to it). */
    suspend fun diffLines(entry: FileEntry): List<DiffLine> = files.diffLines(entry)

    /** Recomputes every open diff tab (the Git status changed); compare and merge tabs hold their own content. */
    fun reloadDiffs() = allTabs.filter { it.isUncommittedDiff() }.forEach(files::loadDiff)

    /** Recomputes the gutter change marks of the open text files (after a save, a commit, a branch switch). */
    fun reloadChanges() = allTabs.filterNot { it.isDiff }.distinctBy { it.entry.path }.forEach(files::loadChanges)

    /** Recomputes the blame annotations of the tabs where they are shown. */
    fun reloadBlames() = allTabs.filter { it.blameVisible }.distinctBy { it.entry.path }.forEach(files::loadBlame)

    /** Recomputes the diff tabs, gutter marks and blame of [entry] alone (saved, the other files are unchanged). */
    fun reloadGitOf(entry: FileEntry) {
        val tabs = allTabs.filter { it.entry.path == entry.path }
        // A commit's diff never changes: only the uncommitted one is recomputed (a compare holds its own lines).
        tabs.filter { it.isUncommittedDiff() }.forEach(files::loadDiff)
        tabs.firstOrNull { !it.isDiff }?.let(files::loadChanges)
        tabs.firstOrNull { it.blameVisible }?.let(files::loadBlame)
    }

    /** Shows or hides the blame annotations of [tab] (Annotate in the Git panel). */
    fun toggleBlame(tab: EditorTab?) {
        if (tab == null || tab.document == null) return
        tab.blameVisible = !tab.blameVisible
        if (tab.blameVisible) files.loadBlame(tab)
    }

    internal fun openIn(
        pane: EditorPane,
        entry: FileEntry,
        diff: Boolean = false,
        commit: CommitDiffTarget? = null,
        compare: Boolean = false,
        merge: Boolean = false,
        review: ReviewMode? = null,
    ): EditorTab {
        val key = tabKey(entry, diff, commit, compare, merge, review)
        val tab =
            pane.tabs.firstOrNull { it.key == key } ?: EditorTab(
                entry,
                relativePath(entry),
                diff,
                commit,
                compare,
                merge,
                review,
            ).also { tab ->
                val insertAt = pane.activeTab?.let { pane.tabs.indexOf(it) + 1 } ?: pane.tabs.size
                pane.tabs.add(insertAt, tab)
                // Same file already open in the other pane: share its content instead of reading it again.
                val twin =
                    allTabs.firstOrNull {
                        it !== tab &&
                            it.key == key &&
                            it.status is TabStatus.Ready
                    }
                if (twin != null) {
                    tab.status = twin.status
                    tab.lineChanges = twin.lineChanges
                    tab.blame = twin.blame
                    tab.blameVisible = twin.blameVisible
                    if (tab.lineChanges.isEmpty()) files.loadChanges(tab)
                } else {
                    files.load(tab)
                }
            }
        activate(tab)
        return tab
    }

    /** Opens [entry] and selects [start]..[end] (immediately, or as soon as the file is loaded). */
    fun openAt(entry: FileEntry, start: TextPos, end: TextPos = start): EditorTab {
        val tab = open(entry)
        val document = tab.document
        tab.revealCaret = true
        if (document != null) document.select(start, end) else tab.pendingSelection = start to end
        return tab
    }

    fun activate(tab: EditorTab) {
        val pane = paneOf(tab) ?: return
        tab.lastActivated = ++activationCounter
        pane.activeTab = tab
        activePane = pane
        // A review is no file to come back to with Ctrl+E.
        if (tab.isReview) return
        recentFiles.remove(tab.entry)
        recentFiles.add(0, tab.entry)
        if (recentFiles.size > MAX_RECENT_FILES) recentFiles.removeAt(recentFiles.lastIndex)
    }

    fun activatePane(pane: EditorPane) {
        if (pane in panes) activePane = pane
    }

    fun close(tab: EditorTab) {
        val pane = paneOf(tab) ?: return
        save(tab) // IntelliJ saves automatically, closing never loses edits
        pane.tabs.remove(tab)
        release(tab)
        if (pane.activeTab == tab) pane.activeTab = pane.tabs.maxByOrNull { it.lastActivated }
        collapseEmptyPane(pane)
    }

    fun closeAll() {
        for (tab in allTabs) {
            save(tab)
            release(tab)
        }
        panes.retainAll { it == panes.first() }
        panes.first().tabs.clear()
        panes.first().activeTab = null
        activePane = panes.first()
    }

    /** Closes, without saving, the tabs of [path] and of everything below it (the file was deleted or moved). */
    fun discard(path: String) {
        val gone = { p: String -> p == path || p.startsWith("$path/") || p.startsWith("$path\\") }
        for (pane in panes.toList()) {
            val removed = pane.tabs.filter { gone(it.entry.path) }
            removed.forEach { it.job?.cancel() }
            pane.tabs.removeAll(removed)
            if (pane.activeTab in removed) pane.activeTab = pane.tabs.maxByOrNull { it.lastActivated }
            collapseEmptyPane(pane)
        }
        recentFiles.removeAll { gone(it.path) }
    }

    fun save(tab: EditorTab? = activeTab) {
        tab?.let(files::save)
    }

    /** A conflict resolved for the disk: the edits of [tab] give way to what another program wrote. */
    fun reloadFromDisk(tab: EditorTab) {
        scope.launch { runCatching { files.reloadFromDisk(tab) } }
    }

    /** A conflict resolved for the edits of [tab]: they are saved over what another program wrote. */
    fun keepEdits(tab: EditorTab) = files.keepEdits(tab)

    /** Opens (or recomputes) the compare of [tab]'s file: the editor's lines against what the disk holds. */
    fun compareWithDisk(tab: EditorTab) {
        val entry = tab.entry
        val pane = paneOf(tab) ?: activePane
        val compareTab = openIn(pane, entry, diff = true, compare = true)
        scope.launch {
            compareTab.status = files.compare(tab)
        }
    }

    /**
     * Saves every modified tab and returns once everything is on disk (before a rename or a Git action); once every
     * tab was tried, fails if one of them could not be saved.
     */
    suspend fun flush() {
        val unsaved = files.flush(allTabs)
        val first = unsaved.firstOrNull() ?: return
        throw IOException("Cannot save ${first.entry.name}: ${first.reason}")
    }

    /**
     * Saves every modified tab, each whatever happens to the others, giving up a save that takes longer than
     * [timeoutMillis] (closing the IDE while the server is out of reach): the files that could not be saved.
     */
    suspend fun saveAllNow(timeoutMillis: Long): List<UnsavedFile> = files.flush(allTabs, timeoutMillis)
}

// A diff of the working tree against HEAD, the one a save or a commit changes (not a commit's, a compare, a merge).
private fun EditorTab.isUncommittedDiff(): Boolean {
    val other = isCompare || isMerge || isReview || commit != null
    return isDiff && !other
}

internal fun EditorState.paneOf(tab: EditorTab): EditorPane? = panes.firstOrNull { tab in it.tabs }

// Stops a closed tab's background work, unless the other pane still shows the same document.
internal fun EditorState.release(tab: EditorTab) {
    tab.changesJob?.cancel()
    tab.blameJob?.cancel()
    val twin = allTabs.firstOrNull { it !== tab && it.key == tab.key }
    if (twin != null && twin.job == null) twin.job = tab.job else tab.job?.cancel()
}

internal fun EditorState.collapseEmptyPane(pane: EditorPane) {
    if (pane.tabs.isEmpty() && panes.size > 1) {
        panes.remove(pane)
        if (activePane == pane) activePane = panes.first()
    }
}
