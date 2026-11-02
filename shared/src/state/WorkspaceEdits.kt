package org.example.kotlineditor.state

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FileChange
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.pathName
import java.io.IOException

// Files an edit rewrites at once on the server.
private const val PARALLEL_REWRITES = 8

/**
 * Applies a rename, a formatting or a quick fix: the open documents are edited (one undo step each, saved with the
 * others), the other files are rewritten on disk, created first when the edit says so. Returns why it failed.
 */
internal suspend fun ProjectState.applyEdit(edit: EditResult): String? {
    val (open, closed) = edit.changes.partition { openDocumentOf(it.path) != null }
    open.forEach { change -> openDocumentOf(change.path)?.applyEdits(change.edits) }
    val failure = rewriteAll(closed)
    if (closed.isNotEmpty()) {
        fileIndex.invalidate()
        tree.refresh()
        editor.reloadGitOf(root)
    }
    val created = closed.lastOrNull { it.create }?.let { entryOfPath(it.path) }
    if (failure == null) created?.let { editor.open(it) }
    return failure
}

// Rewrites the files that are not open, a few at a time (each is a read and a write on the server); why one failed.
private suspend fun ProjectState.rewriteAll(changes: List<FileChange>): String? {
    val slots = Semaphore(PARALLEL_REWRITES)
    val failures =
        coroutineScope {
            changes
                .map { change -> async { slots.withPermit { rewriteOrFailure(change) } } }
                .awaitAll()
        }
    return failures.firstNotNullOfOrNull { it }
}

private suspend fun ProjectState.rewriteOrFailure(change: FileChange): String? =
    try {
        val entry = if (change.create) createIfMissing(change.path) else entryOfPath(change.path)
        if (change.edits.isNotEmpty()) rewrite(entry, change)
        null
    } catch (failure: IOException) {
        "Could not change ${pathName(normalized(change.path))}: ${failure.message}"
    }

private suspend fun ProjectState.createIfMissing(path: String): FileEntry {
    services.fileSystem.fileAt(path)?.let { return it }
    val file = normalized(path)
    val directory =
        FileEntry(
            file.substringBeforeLast('/'),
            pathName(file.substringBeforeLast('/')),
            isDirectory = true,
        )
    return services.fileSystem.createFile(directory, pathName(file))
}

private suspend fun ProjectState.rewrite(entry: FileEntry, change: FileChange) {
    val content = services.fileSystem.read(entry) as? FileContent.Text ?: throw IOException("not a text file")
    val lines = applyEdits(content.lines, change.edits)
    services.fileSystem.write(entry, lines.joinToString(content.lineSeparator))
}
