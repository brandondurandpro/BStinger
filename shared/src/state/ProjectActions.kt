@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.syntax.Structure
import java.io.IOException

private const val MAX_BACK_STEPS = 100

/** Entry of the file at [relativePath] (Git paths are relative to the project root, with `/` separators). */
fun ProjectState.entryAt(relativePath: String): FileEntry =
    FileEntry(
        path = root.path.trimEnd('/', '\\') + "/" + relativePath,
        name = pathName(relativePath),
        isDirectory = false,
    )

/** Opens the diff tab of the file at [relativePath] (a click on a Git panel change). */
fun ProjectState.openDiff(relativePath: String) = editor.openDiff(entryAt(relativePath))

/** Opens the diff of [file] in [commit] (a click on a changed file in the Branches window). */
fun ProjectState.openCommitDiff(commit: GitCommit, file: GitCommitFile) =
    editor.openCommitDiff(entryAt(file.path), CommitDiffTarget(commit.hash, commit.shortHash, file.oldPath))

/** Opens [entry] at [start]..[end], remembering where we came from. */
fun ProjectState.navigate(entry: FileEntry, start: TextPos, end: TextPos = start) {
    editor.activeTab?.let { tab -> tab.document?.let { backStack.addLast(tab.entry to it.caret) } }
    if (backStack.size > MAX_BACK_STEPS) backStack.removeFirst()
    editor.openAt(entry, start, end)
}

fun ProjectState.navigateBack() {
    backStack.removeLastOrNull()?.let { (entry, pos) -> editor.openAt(entry, pos) }
}

/**
 * Ctrl+B: jumps to the declaration of the identifier at the caret, looking in the current file first,
 * then in the whole project (regex-based outline, no compiler).
 */
fun ProjectState.gotoDeclaration(at: TextPos? = null) {
    val tab = editor.activeTab ?: return
    val document = tab.document ?: return
    val pos = at ?: document.caret
    document.identifierAt(pos)?.let { name ->
        scope.launch {
            declarationOf(name, tab.entry, document, pos)?.let { (entry, target) -> navigate(entry, target) }
        }
    }
}

private suspend fun ProjectState.declarationOf(
    name: String,
    file: FileEntry,
    document: Document,
    pos: TextPos,
): Pair<FileEntry, TextPos>? {
    val lines = document.lines.toList()
    val outline =
        withContext(Dispatchers.Default) { Structure.outline(lines, document.language, document.commentStates) }
    val local = outline.firstOrNull { it.name == name && it.line != pos.line }
    if (local != null) return file to TextPos(local.line, local.column)
    val symbols =
        try {
            fileIndex.allSymbols()
        } catch (expected: IOException) {
            emptyList() // server unreachable: no cross-file navigation for now
        }
    return symbols
        .firstOrNull { (entry, d) -> d.name == name && (entry.path != file.path || d.line != pos.line) }
        ?.let { (entry, d) -> entry to TextPos(d.line, d.column) }
}

fun ProjectState.createFile(directory: FileEntry, name: String, isDirectory: Boolean) =
    fileOperation {
        val fs = services.fileSystem
        val created = if (isDirectory) fs.createDirectory(directory, name) else fs.createFile(directory, name)
        tree.expand(directory)
        tree.reload(directory.path)
        tree.reveal(created.path)
        if (!created.isDirectory) editor.open(created)
    }

fun ProjectState.rename(entry: FileEntry, newName: String) =
    fileOperation {
        val reopen = editor.allTabs.any { it.entry.path == entry.path && !it.isDiff }
        editor.flush()
        val renamed = services.fileSystem.rename(entry, newName)
        editor.discard(entry.path)
        tree.parentOf(entry)?.let { tree.reload(it.path) }
        tree.reveal(renamed.path)
        if (reopen) editor.open(renamed)
    }

fun ProjectState.delete(entry: FileEntry) =
    fileOperation {
        services.fileSystem.delete(entry)
        editor.discard(entry.path)
        tree.parentOf(entry)?.let {
            tree.reload(it.path)
            tree.select(it)
        }
    }

// Runs a file operation; failures are shown in the open dialog, success closes it.
private fun ProjectState.fileOperation(block: suspend () -> Unit) {
    scope.launch {
        try {
            block()
            fileDialog = null
            fileIndex.invalidate()
        } catch (e: IOException) {
            fileDialog = fileDialog?.withError(e.message ?: e::class.simpleName.orEmpty())
        }
    }
}

/**
 * IntelliJ synchronizes with the disk when its window regains focus: the tree is re-listed and unmodified
 * open files are reloaded if they were changed by another program.
 */
fun ProjectState.synchronize() {
    tree.refresh()
    fileIndex.invalidate()
    refreshGit()
    for (tab in editor.allTabs.filterNot { it.isDiff }.distinctBy { it.entry.path }) {
        val document = tab.document
        if (document != null && !document.isModified) scope.launch { reloadIfChanged(tab.entry, document) }
    }
}

private suspend fun ProjectState.reloadIfChanged(entry: FileEntry, document: Document) {
    val content =
        try {
            services.fileSystem.read(entry)
        } catch (expected: IOException) {
            null // server unreachable: synchronized at the next focus
        }
    val lines = (content as? FileContent.Text)?.lines
    if (lines != null && !document.isModified && lines != document.lines.toList()) document.reload(lines)
}
