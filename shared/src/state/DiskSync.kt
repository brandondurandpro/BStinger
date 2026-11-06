package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.DiskChanges
import org.example.kotlineditor.model.DiskCursor
import org.example.kotlineditor.model.FileVersion

// Wait before polling again a server that failed to answer (unreachable, or the project is gone).
private const val RETRY_MS = 1_000L
private const val GIT_DIRECTORY = ".git"

/**
 * Follows what other programs change under the project (a terminal, a build, another editor, `git checkout`), as
 * the server's watch tells it (a long poll): the tree, the Git status, the open files and the index follow. A
 * server that lost track of the changes (it restarted) makes the project synchronize everything, as on focus.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ProjectState.followDisk() {
    var cursor = DiskCursor.START
    while (true) {
        val answer =
            try {
                services.disk.changes(root.path, cursor, openPaths())
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(RETRY_MS)
                continue
            }
        // The first answer only says where the changes start: the project was just read.
        val first = cursor == DiskCursor.START
        cursor = answer.cursor
        // An IDE Git action (a pull writing hundreds of files) is not slowed down by reloads while it writes: what it
        // changed comes in one go once it is done, along with what other programs changed meanwhile.
        if (!first && answer.paths.isNotEmpty()) snapshotFlow { git.busy }.first { !it }
        if (!first) applyDiskChanges(answer)
        diskWatched = true
    }
}

// The documents the tabs hold (absolute paths, each once; the diffs of Git are not documents of their own): they
// are what the watch gives a version for, so their changes are decided on it, and the others are read one by one.
private fun ProjectState.openPaths(): List<String> {
    val documents = editor.allTabs.filterNot { it.isDiff }
    return documents.map { it.entry.path }.distinct()
}

private suspend fun ProjectState.applyDiskChanges(changes: DiskChanges) {
    if (!changes.complete) {
        synchronize()
        return
    }
    val separator = root.path.separator()
    val gitPrefix = root.path + separator + GIT_DIRECTORY
    val (inGit, files) = changes.paths.partition { it == gitPrefix || it.startsWith(gitPrefix + separator) }
    if (files.isNotEmpty()) filesChanged(files, changes.versions, separator)
    // A long poll that waited for nothing.
    if (changes.paths.isEmpty()) return
    // A checkout, a commit or a stage: the branch, the status and every editor's diff may have moved.
    git.reloadAfterDiskChange(branchesToo = inGit.isNotEmpty())
}

// [separator] is the one of the disk of the server: on Linux and macOS a backslash is a character of a name.
private fun ProjectState.filesChanged(paths: List<String>, versions: Map<String, FileVersion>, separator: Char) {
    paths
        .map { it.substring(0, it.lastIndexOf(separator).coerceAtLeast(0)) }
        .distinct()
        .forEach(tree::reload)
    fileIndex.invalidate()
    val changed = paths.toSet()
    val tabs = editor.allTabs.filter { !it.isDiff && it.entry.path in changed }
    for (tab in tabs.distinctBy { it.entry.path }) {
        val document = tab.document ?: continue
        val version = versions[tab.entry.path]
        scope.launch {
            // The version travels when the file is open: decided on it, or read as today when there is none.
            val followed =
                if (version != null) {
                    followVersionChange(tab.entry, document, version)
                } else {
                    reloadIfChanged(tab.entry, document)
                }
            if (followed) editor.reloadGitOf(tab.entry)
        }
    }
}

private fun String.separator(): Char = if ('\\' in this && '/' !in this) '\\' else '/'
