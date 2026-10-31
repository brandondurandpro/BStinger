package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter
import java.io.IOException

private const val RECOMPUTE_DELAY_MS = 150L
private const val MEGABYTE = 1024 * 1024

/** Loads tabs' content from the server and saves it back. */
internal class TabFiles(
    private val fileSystem: ProjectFileSystem,
    private val scope: CoroutineScope,
    private val onSaved: (FileEntry) -> Unit,
    private val diffs: suspend (FileEntry) -> List<DiffLine>,
    private val blames: suspend (FileEntry) -> List<BlameLine>,
    private val commitDiffs: suspend (FileEntry, CommitDiffTarget) -> List<DiffLine>,
) {
    fun load(tab: EditorTab) {
        if (tab.isDiff) loadDiff(tab) else loadText(tab)
    }

    /** Recomputes a diff tab (after a save, a commit, a change on disk). */
    fun loadDiff(tab: EditorTab) {
        tab.job?.cancel()
        tab.job =
            scope.launch {
                tab.status =
                    try {
                        val commit = tab.commit
                        TabStatus.Diff(if (commit == null) diffs(tab.entry) else commitDiffs(tab.entry, commit))
                    } catch (e: IOException) {
                        TabStatus.Unsupported("Cannot compute the differences: ${e.message}")
                    }
            }
    }

    /** Recomputes the gutter marks of a text tab (after its content changed or HEAD moved). */
    fun loadChanges(tab: EditorTab) {
        if (tab.isDiff) return
        tab.changesJob?.cancel()
        tab.changesJob =
            scope.launch {
                tab.lineChanges =
                    try {
                        lineChangesOf(diffs(tab.entry))
                    } catch (_: IOException) {
                        emptyList()
                    }
            }
    }

    /** Loads the blame annotations of a text tab; they are shown when the tab's gutter is annotated. */
    fun loadBlame(tab: EditorTab) {
        if (tab.isDiff) return
        tab.blameJob?.cancel()
        tab.blameJob =
            scope.launch {
                tab.blame =
                    try {
                        blames(tab.entry)
                    } catch (_: IOException) {
                        emptyList()
                    }
            }
    }

    private fun loadText(tab: EditorTab) {
        tab.job =
            scope.launch {
                val content =
                    try {
                        fileSystem.read(tab.entry)
                    } catch (e: IOException) {
                        FileContent.Error("the server is unreachable (${e.message})")
                    }
                tab.status =
                    when (content) {
                        is FileContent.Text -> {
                            TabStatus.Ready(open(tab, content))
                        }

                        FileContent.Binary -> {
                            TabStatus.Unsupported("Binary file: content is not displayed")
                        }

                        is FileContent.TooLarge -> {
                            TabStatus.Unsupported("File is too large to open (${content.size / MEGABYTE} MB)")
                        }

                        is FileContent.Error -> {
                            TabStatus.Unsupported("Cannot read file: ${content.message}")
                        }
                    }
                if (tab.status is TabStatus.Ready) loadChanges(tab)
            }
    }

    /** Saves [tab] in the background; if the server is unreachable it stays modified and is saved again later. */
    fun save(tab: EditorTab) {
        if (tab.document?.isModified == true) {
            scope.launch { runCatching { write(tab) } }
        }
    }

    /** Saves every modified tab and returns once everything is on disk (closing the project or the IDE). */
    suspend fun flush(tabs: List<EditorTab>) = tabs.forEach { write(it) }

    private suspend fun write(tab: EditorTab) {
        val document = tab.document
        if (document != null && document.isModified) {
            val version = document.version
            fileSystem.write(tab.entry, document.text())
            if (document.version == version) document.markSaved()
            onSaved(tab.entry)
        }
    }

    private suspend fun CoroutineScope.open(tab: EditorTab, content: FileContent.Text): Document {
        val language = Language.fromFileName(tab.entry.name)
        val document =
            withContext(Dispatchers.Default) {
                Document(content.lines, language, content.lineSeparator).also {
                    it.commentStates = SyntaxHighlighter.blockCommentStates(content.lines, language)
                    it.folding.regions = FoldingModel.computeRegions(content.lines, language)
                }
            }
        if (language.slashComments) launch { recomputeOnEdit(document, language) }
        tab.pendingSelection?.let { (start, end) -> document.select(start, end) }
        tab.pendingSelection = null
        return document
    }

    // Comment boundaries and fold regions move on edit: recompute them off the UI thread once typing pauses.
    @OptIn(FlowPreview::class)
    private suspend fun recomputeOnEdit(document: Document, language: Language) {
        snapshotFlow { document.version }.debounce(RECOMPUTE_DELAY_MS).collect {
            val snapshot = document.lines.toList()
            val (states, regions) =
                withContext(Dispatchers.Default) {
                    SyntaxHighlighter.blockCommentStates(
                        snapshot,
                        language,
                    ) to FoldingModel.computeRegions(snapshot, language)
                }
            if (document.lineCount == snapshot.size) {
                document.commentStates = states
                document.folding.regions = regions
            }
        }
    }
}
