package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FileChange
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.pathName
import java.io.IOException

/** Code intelligence of a project: its language servers, and what it does on the project's files. */
internal fun ProjectState.insightBackend(): InsightBackend? {
    val servers = languageServers ?: return null
    return object : InsightBackend {
        override val intelligence = servers.intelligence

        override fun versionOf(path: String, document: Document): Int? = servers.versionOf(path, document)
    }
}

/** What code intelligence does in [project]: opens files, applies edits, searches declarations by name. */
internal class ProjectInsightHost(private val project: () -> ProjectState) : InsightHost {
    override fun navigate(path: String, start: TextPos) {
        val target = project()
        target.navigate(target.entryOfPath(path), start, start)
    }

    override suspend fun apply(edit: EditResult): String? = project().applyEdit(edit)

    override fun declarationByName(at: TextPos) = project().declarationByName(at)

    override fun relativePath(path: String): String {
        val root = normalized(project().root.path)
        val file = normalized(path)
        return if (file.startsWith("$root/")) file.removePrefix("$root/") else file
    }
}

/** Entry of the file at the absolute [path] (as the server names it). */
internal fun ProjectState.entryOfPath(path: String): FileEntry {
    val open = editor.allTabs.firstOrNull { normalized(it.entry.path) == normalized(path) }
    return open?.entry ?: FileEntry(path = path, name = pathName(normalized(path)), isDirectory = false)
}

/** The document of [path] open in an editor (not a diff), null when it is not open. */
internal fun ProjectState.openDocumentOf(path: String): Document? =
    editor.allTabs
        .filterNot { it.isDiff }
        .firstOrNull { normalized(it.entry.path) == normalized(path) }
        ?.document

/**
 * Applies a rename, a formatting or a quick fix: the open documents are edited (one undo step each, saved with the
 * others), the other files are rewritten on disk, created first when the edit says so. Returns why it failed.
 */
internal suspend fun ProjectState.applyEdit(edit: EditResult): String? {
    var created: FileEntry? = null
    for (change in edit.changes) {
        val document = openDocumentOf(change.path)
        if (document != null) {
            document.applyEdits(change.edits)
            continue
        }
        try {
            val entry = if (change.create) createIfMissing(change.path) else entryOfPath(change.path)
            if (change.create) created = entry
            if (change.edits.isNotEmpty()) rewrite(entry, change)
        } catch (failure: IOException) {
            return "Could not change ${pathName(normalized(change.path))}: ${failure.message}"
        }
    }
    if (edit.changes.any { openDocumentOf(it.path) == null }) {
        fileIndex.invalidate()
        tree.refresh()
        editor.reloadGitOf(root)
    }
    created?.let { editor.open(it) }
    return null
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

/** Follows the documents with a language server, their problems, and the restarts of the servers. */
internal fun ProjectState.followInsight() {
    val servers = languageServers ?: return
    scope.launch {
        snapshotFlow {
            editor.allTabs
                .filterNot { it.isDiff }
                .filter { Languages.languageOf(it.entry.path) != null }
                .mapNotNull { tab -> tab.document?.let { tab.entry.path to it } }
                .toMap()
        }.distinctUntilChanged().collect { insight.track(it) }
    }
    scope.launch { snapshotFlow { servers.diagnostics }.collect { insight.applyDiagnostics(it) } }
    // A new process (a restart, another server chosen) knows nothing of the previous counts.
    scope.launch {
        snapshotFlow { servers.status.servers.map { it.id to it.generation } }
            .distinctUntilChanged()
            .collect { insight.refreshAll() }
    }
}

/** Whether the file of [tab] has a language server in this project. */
fun ProjectState.hasInsight(tab: EditorTab): Boolean =
    languageServers != null && Languages.languageOf(tab.entry.path) != null

/** Runs [action] on the active editor when its file has a language server. */
fun ProjectState.withInsight(action: CodeInsightState.(path: String, document: Document) -> Unit) {
    val tab = editor.activeTab ?: return
    val document = tab.document ?: return
    if (hasInsight(tab)) insight.action(tab.entry.path, document)
}
