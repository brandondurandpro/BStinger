@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.state

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.syntax.Structure
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame
import java.io.IOException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.NoSuchFileException

internal const val MAX_BACK_STEPS = 100

/** Entry of the file at [relativePath] (Git paths are relative to the project root, with `/` separators). */
fun ProjectState.entryAt(relativePath: String): FileEntry =
    FileEntry(
        path = root.path.trimEnd('/', '\\') + "/" + relativePath,
        name = pathName(relativePath),
        isDirectory = false,
    )

/**
 * Opens the file of the diff [tab] (its Open File button, F4), at the first line the diff changed. The line numbers
 * of a commit's diff are those of that commit's version, which the current file may have moved since.
 */
fun ProjectState.openFileOfDiff(tab: EditorTab) {
    if (!tab.isDiff) return
    val lines = (tab.status as? TabStatus.Diff)?.lines.orEmpty()
    navigate(tab.entry, TextPos(firstChangedLine(lines), 0))
}

// Zero-based line of the first change in the new version: an added line, or where removed lines used to be.
fun firstChangedLine(lines: List<DiffLine>): Int {
    val first = lines.indexOfFirst { it.kind == DiffKind.Added || it.kind == DiffKind.Removed }
    if (first < 0) return 0
    val after = lines.drop(first).firstOrNull { it.newLine > 0 }
    val before = lines.take(first).lastOrNull { it.newLine > 0 }
    val newLine =
        when {
            after != null -> after.newLine
            before != null -> before.newLine + 1
            else -> 1
        }
    return (newLine - 1).coerceAtLeast(0)
}

fun ProjectState.navigateBack() {
    backStack.removeLastOrNull()?.let { (entry, pos) -> editor.openAt(entry, pos) }
}

/**
 * Ctrl+B and Ctrl+click: jumps to the declaration of the identifier at [at] (the caret by default). The language
 * server of the file finds it when there is one (its usages when already on it); otherwise it is searched by name.
 */
fun ProjectState.gotoDeclaration(at: TextPos? = null) {
    val tab = editor.activeTab
    val document = tab?.document ?: return
    val pos = at ?: document.caret
    val insightful = hasInsight(tab)
    // A link of the language server (a required file, an address) is followed rather than the word in it.
    val followed = insightful && insight.followLink(document, pos)
    when {
        followed -> Unit
        insightful -> insight.gotoDeclaration(tab.entry.path, document, pos)
        else -> declarationByName(pos)
    }
}

/**
 * The declaration of the identifier at [pos] by name, looking in the current file first, then in the whole project
 * (regex-based outline, no compiler).
 */
fun ProjectState.declarationByName(pos: TextPos) {
    scope.launch { goToDeclarationByName(pos) }
}

/** Goes to the declaration named like the word at [pos], found in the file or the project; false if there is none. */
internal suspend fun ProjectState.goToDeclarationByName(pos: TextPos): Boolean {
    val tab = editor.activeTab
    val document = tab?.document
    val name = document?.identifierAt(pos)
    val found = name?.let { declarationOf(it, tab.entry, document, pos) }
    found?.let { (entry, target) -> navigate(entry, target) }
    return found != null
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
        val path = directory.path.trimEnd('/', '\\') + separatorOf(directory.path) + name
        // A file that exists already is not created: nothing to adapt.
        // Without a running language server nothing is asked: the creation is the only request.
        if (adaptsCode() && services.fileSystem.fileAt(path) == null) {
            adaptCodeTo(FileOperation.Create, OperatedFile(path, directory = isDirectory))
        }
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
        adaptCodeToRename(entry, newName)
        editor.flush()
        val renamed = services.fileSystem.rename(entry, newName)
        editor.discard(entry.path)
        tree.parentOf(entry)?.let { tree.reload(it.path) }
        tree.reveal(renamed.path)
        if (reopen) editor.open(renamed)
    }

fun ProjectState.delete(entry: FileEntry) =
    fileOperation {
        if (adaptsCode()) adaptCodeTo(FileOperation.Delete, OperatedFile(entry.path, directory = entry.isDirectory))
        services.fileSystem.delete(entry)
        editor.discard(entry.path)
        tree.parentOf(entry)?.let {
            tree.reload(it.path)
            tree.select(it)
        }
    }

// The code adapted to the rename of [entry] to [newName], once the rename is known to be possible: the code must not
// change for a rename that then fails (a file of that name exists already, the file is gone).
private suspend fun ProjectState.adaptCodeToRename(entry: FileEntry, newName: String) {
    val validName = newName.isNotBlank() && '/' !in newName && '\\' !in newName
    // Only the case changes: the same file on some disks, and no new name for the code.
    val sameName = newName.equals(entry.name, ignoreCase = true)
    // Without a running language server nothing is asked: the rename is the only request.
    if (!adaptsCode()) return
    if (!validName || sameName) return
    val newPath = entry.path.dropLast(entry.name.length) + newName
    val fs = services.fileSystem
    // Deleted by another program meanwhile: the rename fails, the code stays.
    if (fs.fileAt(entry.path) == null) throw NoSuchFileException(entry.path)
    if (fs.fileAt(newPath) != null) throw FileAlreadyExistsException(newPath)
    adaptCodeTo(FileOperation.Rename, OperatedFile(entry.path, newPath, entry.isDirectory))
}

// The edits the language servers make before [operation] of [file] (`workspace/willRenameFiles`: the class renamed
// with its file), applied. The operation runs anyway when no server is ready or answers.
private suspend fun ProjectState.adaptCodeTo(operation: FileOperation, file: OperatedFile) {
    val servers = languageServers ?: return
    val edit = servers.intelligence.willChangeFiles(operation, listOf(file))
    // A file the edit could not change does not stop the operation: retrying it would apply the others twice.
    if (edit.changes.isNotEmpty()) applyEdit(edit)
}

// Whether a language server runs to adapt the code to a file operation.
private fun ProjectState.adaptsCode(): Boolean = languageServers?.anyRunning == true

// The separator of the paths of the server machine, as [path] uses it.
private fun separatorOf(path: String): String = if ('\\' in path && '/' !in path) "\\" else "/"

// Runs a file operation; failures are shown in the open dialog, success closes it.
private fun ProjectState.fileOperation(block: suspend () -> Unit) {
    scope.launch {
        try {
            // From the confirmation to the frame that shows the tree changed; a failure is shown, and measured.
            ClientTelemetry.measureToFrame(Operation.TreeFileOperation) {
                block()
                fileDialog = null
                fileIndex.invalidate()
            }
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
    git.refresh()
    for (tab in editor.allTabs.filterNot { it.isDiff }.distinctBy { it.entry.path }) {
        val document = tab.document
        if (document != null) scope.launch { reloadIfChanged(tab.entry, document) }
    }
}

/**
 * Follows the disk in [document] when another program changed its file: reloaded when it has no edit of its own,
 * in conflict otherwise (the user chooses, see `DiskBanner`). True when it was reloaded.
 */
internal suspend fun ProjectState.reloadIfChanged(entry: FileEntry, document: Document): Boolean {
    val stamp = document.saveStamp
    val content =
        try {
            services.fileSystem.read(entry)
        } catch (expected: IOException) {
            null // server unreachable: synchronized at the next focus
        }
    // A save of the IDE ran meanwhile: the disk holds what it wrote, which the read may or may not have seen.
    val overlappedSave = stamp % 2 == 1 || document.saveStamp != stamp
    val lines = (content as? FileContent.Text)?.lines
    if (lines == null || overlappedSave) return false
    val changedOutside = lines.hashCode() != document.diskLinesHash
    val stale = !document.isModified && lines != document.lines.toList()
    when {
        stale -> document.reload(lines)
        changedOutside && document.isModified -> document.diskConflict = true
        else -> Unit
    }
    return stale
}
