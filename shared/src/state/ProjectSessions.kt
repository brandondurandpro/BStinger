package org.example.kotlineditor.state

import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SessionFile
import java.io.IOException

/** What a session reopens, fetched from the server before anything is shown. See [restoreSession]. */
private class RestoredSession(
    /** The files still there, in tab order, with their caret. */
    val files: List<Pair<FileEntry, TextPos>>,
    val activeFile: String?,
    /** The tree down to the active file, when it follows the active file. */
    val tree: TreeReveal?,
)

/**
 * Reopens the files of the previous session, with their caret and the active tab, and the tree down to the active
 * file. Everything is fetched at once, then shown in the same frame: the tabs do not appear one after the other and
 * the tree does not unfold the directory of each of them. Waits up to [waitMillis] so that the project appears as it
 * was left; past that, the project is shown at once and the session restored when the server answers.
 */
suspend fun ProjectState.restoreSession(waitMillis: Long) {
    val fetching = scope.async { fetchSession() }
    val fetched = withTimeoutOrNull(waitMillis) { fetching.await() }
    when {
        fetched != null -> showSession(fetched)

        // Late: once the session arrives.
        fetching.isActive -> scope.launch { fetching.await()?.let { showSession(it) } }
    }
}

// The session, then its files and the listings of the tree, requested together: two round trips in all.
private suspend fun ProjectState.fetchSession(): RestoredSession? {
    val session = runCatching { services.sessions.load(root.path) }.getOrNull() ?: return null
    return coroutineScope {
        val activeFile = session.activeFile ?: session.openFiles.lastOrNull()?.path
        val revealed = activeFile?.takeIf { autoSelectOpenedFile }
        val listings = revealed?.let { async { tree.listingsTo(it) } }
        val files = session.openFiles.map { file -> async { fileOf(file) } }.awaitAll()
        RestoredSession(files.filterNotNull(), activeFile, listings?.await())
    }
}

private suspend fun ProjectState.fileOf(file: SessionFile): Pair<FileEntry, TextPos>? {
    val entry =
        try {
            services.fileSystem.fileAt(file.path)
        } catch (expected: IOException) {
            null // unreachable now: the file is simply not reopened
        }
    return entry?.let { it to TextPos(file.line, file.column) }
}

private fun ProjectState.showSession(restored: RestoredSession) {
    // A session arriving late does not take the place of the file the user opened meanwhile.
    val chosenByUser = editor.activeTab
    Snapshot.withMutableSnapshot {
        val opened = restored.files.map { (entry, caret) -> entry.path to editor.openAt(entry, caret) }
        val active = opened.firstOrNull { it.first == restored.activeFile }?.second
        val shown = chosenByUser ?: active
        shown?.let(editor::activate)
        // Revealed only when the session's active file is shown, as following the active tab would.
        val revealed = restored.tree?.takeIf { chosenByUser == null && active != null }
        revealed?.let(tree::show)
    }
}

fun ProjectState.session(): ProjectSession =
    ProjectSession(
        editor.allTabs.filterNot { it.isDiff }.distinctBy { it.entry.path }.map { tab ->
            val caret = tab.document?.caret ?: tab.pendingSelection?.first ?: TextPos(0, 0)
            SessionFile(tab.entry.path, caret.line, caret.column)
        },
        editor.activeTab
            ?.takeUnless { it.isDiff }
            ?.entry
            ?.path,
    )
