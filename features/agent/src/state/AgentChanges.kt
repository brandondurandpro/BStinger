package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitFileStatus
import java.io.IOException

// More files than this and the lines added and removed are not counted for the rest: each count is a Git process.
private const val MAX_COUNTED_FILES = 100

// Files whose lines are counted at the same time.
private const val PARALLEL_DIFFS = 8

/** A file the agent changed where it works, with the lines its changes add and remove. */
class ChangedFile(val path: String, val status: GitFileStatus, val added: Int, val removed: Int) {
    val name: String get() = path.substringAfterLast('/')
    val directory: String get() = path.substringBeforeLast('/', "")
}

/** The uncommitted changes of the place where the agent of a conversation works, to review them. */
@Stable
class ChangesState {
    /** The changed files, sorted by path; empty before the first reading and when nothing changed. */
    var files: List<ChangedFile> by mutableStateOf(emptyList())
        internal set

    /** Whether the list is open under the conversation. */
    var expanded by mutableStateOf(false)

    /** Why the changes could not be read or reverted, null when nothing failed. */
    var failure: String? by mutableStateOf(null)
        internal set

    val added: Int get() = files.sumOf { it.added }
    val removed: Int get() = files.sumOf { it.removed }
}

/** Reads the changes of [conversation] again whenever it is shown, and when its agent finishes its turn. */
internal fun AgentState.followChanges() {
    project.scope.launch {
        snapshotFlow { current?.let { it to it.busy } }
            .distinctUntilChanged()
            .collect { shown -> if (shown != null && !shown.second) refreshChanges(shown.first) }
    }
}

/** Reads the uncommitted changes of the place [conversation] works in, with their lines counted. */
fun AgentState.refreshChanges(conversation: Conversation) {
    val workspace = conversation.workspace ?: return
    val root = workspace.root.path
    project.scope.launch {
        try {
            val status = project.services.git.status(root)
            val sorted = status.entries.sortedBy { it.key }
            // The lines of each file are counted together, not one Git process after the other.
            val limit = Semaphore(PARALLEL_DIFFS)
            conversation.changes.files =
                coroutineScope {
                    sorted.mapIndexed { index, entry ->
                        val counted = index < MAX_COUNTED_FILES
                        async { limit.withPermit { changedFile(root, entry.key, entry.value, counted) } }
                    }.awaitAll()
                }
            conversation.changes.failure = null
        } catch (failed: IOException) {
            conversation.changes.failure = "Cannot read the changes: ${failed.message}"
        }
    }
}

private suspend fun AgentState.changedFile(root: String, path: String, status: GitFileStatus, count: Boolean): ChangedFile {
    if (!count) return ChangedFile(path, status, 0, 0)
    val lines = runCatching { project.services.git.diff(root, path) }.getOrDefault(emptyList())
    return ChangedFile(path, status, lines.count { it.kind == DiffKind.Added }, lines.count { it.kind == DiffKind.Removed })
}

/** Opens the diff of [file] in the tabs of [conversation]: what the agent changed in it, against HEAD. */
fun AgentState.openChange(conversation: Conversation, file: ChangedFile) {
    val workspace = conversation.workspace ?: return
    val root = workspace.root.path.trimEnd('/')
    workspace.editor.openDiff(FileEntry(path = "$root/${file.path}", name = file.name, isDirectory = false))
    conversation.showsChat = false
}

/**
 * Throws away the uncommitted changes of [paths] in the place [conversation] works in: modified files go back to what
 * HEAD holds, new files are deleted. The tree, the Git panel and the open editors follow.
 */
fun AgentState.revertChanges(conversation: Conversation, paths: List<String>) {
    val workspace = conversation.workspace ?: return
    if (paths.isEmpty()) return
    project.scope.launch {
        try {
            project.services.git.revert(workspace.root.path, paths)
            conversation.changes.failure = null
        } catch (failed: IOException) {
            conversation.changes.failure = "Cannot revert: ${failed.message}"
        }
        workspace.synchronize()
        refreshChanges(conversation)
    }
}
