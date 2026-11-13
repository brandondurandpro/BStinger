package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.pathName
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.treesitter.LineSpans
import org.example.kotlineditor.syntax.treesitter.Snippet
import org.example.kotlineditor.syntax.treesitter.highlightSnippets

/** Code intelligence of a project: its language servers, and what it does on the project's files. */
internal fun ProjectState.insightBackend(): InsightBackend? {
    val servers = languageServers ?: return null
    return object : InsightBackend {
        override val intelligence = servers.intelligence

        override val indexing: Boolean
            get() = servers.status.servers.any { it.state == LanguageServerState.Indexing }

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

    override suspend fun declarationByName(at: TextPos) = project().goToDeclarationByName(at)

    override fun openUrl(url: String) = project().openUrl(url)

    override fun relativePath(path: String): String {
        val root = normalized(project().root.path)
        val file = normalized(path)
        return if (file.startsWith("$root/")) file.removePrefix("$root/") else file
    }

    override suspend fun colorsOf(snippets: List<Snippet>): List<LineSpans?> {
        val grammars = project().grammars ?: return snippets.map { null }
        return grammars.highlightSnippets(snippets)
    }
}

/** The declarations of [document] from its language server, null when none answers. */
internal suspend fun ProjectState.outlineFromServer(document: Document): List<Declaration>? {
    val servers = languageServers ?: return null
    val tab = editor.allTabs.filterNot { it.isDiff }.firstOrNull { it.document === document }
    val path = tab?.entry?.path
    val version = path?.let { servers.versionOf(it, document) }
    val result = version?.let { servers.intelligence.outline(path, it) }
    return result?.declarations?.takeIf { result.status == FeatureStatus.Ready }
}

/** The declarations of the project named like [query], from its language servers. */
internal suspend fun ProjectState.symbolsFromServers(query: String): List<SymbolMatch> {
    val servers = languageServers ?: return emptyList()
    val result = servers.intelligence.workspaceSymbols(query)
    return result.symbols.mapNotNull { symbol ->
        fileIndex.symbolMatch(
            entryOfPath(symbol.path),
            symbol.declaration,
            query,
        )
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

/** Follows the documents with a language server, their problems, and the restarts of the servers. */
internal fun ProjectState.followInsight() {
    val servers = languageServers ?: return
    scope.launch {
        snapshotFlow {
            editor.allTabs
                .filterNot { it.isDiff }
                .filter { Languages.hasInsight(it.entry.path) }
                .mapNotNull { tab -> tab.document?.let { tab.entry.path to it } }
                .toMap()
        }.distinctUntilChanged().collect { insight.track(it) }
    }
    // Basic keeps the servers but not their decorations (inlay hints, semantic colors, code lenses, links).
    scope.launch { snapshotFlow { lspLevel == LspLevel.Full }.collect { insight.decorated = it } }
    scope.launch { snapshotFlow { servers.diagnostics }.collect { insight.applyDiagnostics(it) } }
    // A new process (a restart, another server chosen) knows nothing of the previous counts. After a restart of the IDE
    // server its language servers start again at the same generation: their pid tells the new processes apart. Once a
    // server indexed the project it finds usages it could not before (intelephense finds none meanwhile).
    scope.launch {
        snapshotFlow {
            servers.status.servers.map {
                ServerIdentity(
                    it.id,
                    it.generation,
                    it.pid,
                    it.state == LanguageServerState.Indexing,
                )
            }
        }.distinctUntilChanged()
            .collect { insight.refreshAll() }
    }
}

/** Whether the file of [tab] has a language server in this project. */
fun ProjectState.hasInsight(tab: EditorTab): Boolean = languageServers != null && Languages.hasInsight(tab.entry.path)

/** Runs [action] on the active editor when its file has a language server. */
fun ProjectState.withInsight(action: CodeInsightState.(path: String, document: Document) -> Unit) {
    val tab = editor.activeTab ?: return
    val document = tab.document ?: return
    if (hasInsight(tab)) insight.action(tab.entry.path, document)
}

// What makes the open documents ask their code intelligence again when it changes.
private data class ServerIdentity(val id: String, val generation: Long, val pid: Long?, val indexing: Boolean)
