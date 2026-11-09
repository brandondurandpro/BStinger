package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileChangedException
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileVersion
import org.example.kotlineditor.model.GitLineChange
import org.example.kotlineditor.model.OutcomeUnknownException
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter
import org.example.kotlineditor.syntax.treesitter.GrammarSpec
import org.example.kotlineditor.syntax.treesitter.GrammarStore
import org.example.kotlineditor.syntax.treesitter.SyntaxSession
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame
import org.example.kotlineditor.telemetry.sizeBucket
import java.io.IOException
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean

private const val RECOMPUTE_DELAY_MS = 150L
private const val CHANGES_DELAY_MS = 150L
private const val MEGABYTE = 1024 * 1024

/** A file whose edits could not be saved, and why (shown to the user, who may try again). */
data class UnsavedFile(val entry: FileEntry, val relativePath: String, val reason: String)

// The saves of one document, one at a time and in the order they were asked: two writes of a file in flight
// together may land in any order, leaving the older text on disk while the editor says it is saved.
private class SaveQueue {
    val mutex = Mutex()

    // A save waits for the one running: asking again changes nothing, it writes the text as it is when it starts.
    val waiting = AtomicBoolean(false)
}

/** Loads tabs' content from the server and saves it back. */
@Suppress("TooManyFunctions")
internal class TabFiles(
    private val fileSystem: ProjectFileSystem,
    private val scope: CoroutineScope,
    private val onSaved: (FileEntry) -> Unit,
    private val diffs: suspend (FileEntry) -> List<DiffLine>,
    private val headLines: suspend (FileEntry) -> List<String>?,
    private val blames: suspend (FileEntry) -> List<BlameLine>,
    private val commitDiffs: suspend (FileEntry, CommitDiffTarget) -> List<DiffLine>,
    private val grammars: GrammarStore?,
) {
    // By document: the two panes may show one document, through two tabs.
    private val saveQueues = WeakHashMap<Document, SaveQueue>()

    fun load(tab: EditorTab) {
        when {
            // A compare tab carries its lines: [EditorState.compareWithDisk] computed and put them there.
            tab.isCompare -> Unit

            tab.isDiff -> loadDiff(tab)

            else -> loadText(tab)
        }
    }

    /** Recomputes a diff tab (after a save, a commit, a change on disk). */
    fun loadDiff(tab: EditorTab) {
        tab.job?.cancel()
        tab.job =
            scope.launch {
                val commit = tab.commit
                val operation = if (commit == null) Operation.EditorDiff else Operation.GitCommitDiff
                ClientTelemetry.measureToFrame(operation, languageOf(tab), ::sizeOf) {
                    val status =
                        try {
                            TabStatus.Diff(if (commit == null) diffs(tab.entry) else commitDiffs(tab.entry, commit))
                        } catch (e: IOException) {
                            TabStatus.Unsupported("Cannot compute the differences: ${e.message}")
                        }
                    tab.status = status
                    status
                }
            }
    }

    /**
     * Recomputes the gutter marks of a text tab (after its content changed or HEAD moved). A tracked file is compared
     * with its lines in HEAD, which the tab keeps to follow the edits (see [followChanges]); the others take the
     * differences the server computes on the disk.
     */
    fun loadChanges(tab: EditorTab) {
        if (tab.isDiff) return
        tab.changesJob?.cancel()
        tab.changesJob =
            scope.launch {
                tab.lineChanges =
                    try {
                        val head = headLines(tab.entry)
                        tab.headLines = head
                        val document = tab.document
                        if (head != null && document != null) {
                            changesAgainst(document, head) ?: tab.lineChanges
                        } else {
                            changesOfUntracked(tab)
                        }
                    } catch (_: IOException) {
                        emptyList()
                    }
            }
    }

    // The marks of a file that has no lines in HEAD: the server's differences. When it reports some, the file is new
    // to Git (untracked): it is followed against an empty HEAD, so every line is added, and the marks follow the typing.
    private suspend fun changesOfUntracked(tab: EditorTab): List<GitLineChange> {
        val changes = lineChangesOf(diffs(tab.entry))
        if (changes.isNotEmpty()) tab.headLines = emptyList()
        return changes
    }

    // The marks of [document] as it is now against [head], null when it was edited while they were computed (the
    // next computation follows that edit).
    private suspend fun changesAgainst(document: Document, head: List<String>): List<GitLineChange>? {
        val version = document.version
        val snapshot = document.lines.toList()
        val changes =
            withContext(Dispatchers.Default) {
                val lines = if (snapshot.lastOrNull() == "") snapshot.dropLast(1) else snapshot
                lineChangesOf(CompareLines.diff(head, lines))
            }
        return changes.takeIf { document.version == version }
    }

    // The gutter marks follow what is typed, not only what is saved: they are computed again once typing pauses.
    @OptIn(FlowPreview::class)
    private suspend fun followChanges(tab: EditorTab, document: Document) {
        snapshotFlow { document.version }.debounce(CHANGES_DELAY_MS).collect {
            val head = tab.headLines
            val changes = head?.let { changesAgainst(document, it) }
            if (changes != null) tab.lineChanges = changes
        }
    }

    /** Loads the blame annotations of a text tab; they are shown when the tab's gutter is annotated. */
    fun loadBlame(tab: EditorTab) {
        if (tab.isDiff) return
        tab.blameJob?.cancel()
        tab.blameJob =
            scope.launch {
                ClientTelemetry.measureToFrame<List<BlameLine>>(
                    Operation.EditorBlame,
                    languageOf(tab),
                    { linesAttributes(it.size) },
                ) {
                    val lines =
                        try {
                            blames(tab.entry)
                        } catch (_: IOException) {
                            emptyList()
                        }
                    tab.blame = lines
                    lines
                }
            }
    }

    private fun loadText(tab: EditorTab) {
        tab.job =
            scope.launch {
                // From the click to the frame that shows the file.
                ClientTelemetry.measureToFrame(Operation.EditorOpenFile, languageOf(tab), ::sizeOf) {
                    loadTextNow(tab)
                    tab.status
                }
            }
    }

    private suspend fun CoroutineScope.loadTextNow(tab: EditorTab) {
        val content = readDisk(tab)
        val unsupported = notText(content)
        if (content is FileContent.Text) {
            tab.status = TabStatus.Ready(open(tab, content))
        } else {
            tab.status = checkNotNull(unsupported)
        }
        if (tab.status is TabStatus.Ready) loadChanges(tab)
    }

    /** The status of the conflict banner's Compare: the document's lines against what the disk holds now. */
    suspend fun compare(tab: EditorTab): TabStatus {
        val content = readDisk(tab)
        val editorLines = tab.document?.lines?.toList()
        val compared = diffAgainstDisk(content, editorLines)
        return when {
            compared != null -> compared
            editorLines == null -> TabStatus.Unsupported("The file is not open")
            else -> checkNotNull(notText(content))
        }
    }

    // The diff view of the disk's content against the editor's lines, when both were there as text; the diff runs
    // off the UI thread, once per open of the compare.
    private suspend fun diffAgainstDisk(content: FileContent, editorLines: List<String>?): TabStatus? =
        if (content is FileContent.Text && editorLines != null) {
            withContext(Dispatchers.Default) { TabStatus.Diff(CompareLines.diff(content.lines, editorLines)) }
        } else {
            null
        }

    // What a tab shows when a read result is not openable as text, `null` when the file may open.
    private fun notText(content: FileContent): TabStatus? =
        when (content) {
            is FileContent.Binary -> {
                TabStatus.Unsupported("Binary file: content is not displayed")
            }

            is FileContent.TooLarge -> {
                TabStatus.Unsupported("File is too large to open (${content.size / MEGABYTE} MB)")
            }

            is FileContent.Error -> {
                TabStatus.Unsupported("Cannot read file: ${content.message}")
            }

            is FileContent.Text -> {
                null
            }
        }

    // Reads the tab's file; a server out of reach is a read error the tab shows like any other.
    private suspend fun readDisk(tab: EditorTab): FileContent =
        try {
            fileSystem.read(tab.entry)
        } catch (e: IOException) {
            FileContent.Error("the server is unreachable (${e.message})")
        }

    /**
     * Saves [tab] in the background; if the server is unreachable it stays modified and is saved again later. A save
     * asked while one of the same document runs is done after it, once, with the text as it is then.
     */
    fun save(tab: EditorTab) {
        val document = tab.document ?: return
        val queue = queueOf(document)
        // When a save of this document waits already, it will write the text as it is when it starts.
        val queued = document.isModified && queue.waiting.compareAndSet(false, true)
        if (queued) launchSave(tab, queue)
    }

    private fun launchSave(tab: EditorTab, queue: SaveQueue) {
        scope.launch {
            var started = false
            try {
                runCatching {
                    queue.mutex.withLock {
                        started = true
                        queue.waiting.set(false)
                        write(tab)
                    }
                }
            } finally {
                // Cancelled while it waited (the project closed): the next save is not taken for this one.
                if (!started) queue.waiting.set(false)
            }
        }
    }

    private fun queueOf(document: Document): SaveQueue =
        synchronized(saveQueues) { saveQueues.getOrPut(document, ::SaveQueue) }

    /** Replaces the edits of [tab] with what another program wrote (the user chose the disk in a conflict). */
    suspend fun reloadFromDisk(tab: EditorTab) {
        val document = tab.document ?: return
        val content = fileSystem.read(tab.entry)
        if (content is FileContent.Text) document.reload(content.lines, content.version)
    }

    /** Saves the edits of [tab] over what another program wrote (the user chose them in a conflict). */
    fun keepEdits(tab: EditorTab) {
        val document = tab.document ?: return
        document.diskConflict = false
        // The user chose these edits: the save is not checked against the version the document knew, the file
        // changed since, and the user wants the disk to hold them. The write records the version it leaves.
        document.diskVersion = null
        save(tab)
    }

    /**
     * Saves every modified tab of [tabs], each whatever happens to the others, and returns once all are done (closing
     * the project or the IDE, before a rename or a Git action): the files that could not be saved, with why. A save
     * that takes longer than [timeoutMillis] (the server is out of reach) is given up, its tab left modified.
     */
    suspend fun flush(tabs: List<EditorTab>, timeoutMillis: Long = Long.MAX_VALUE): List<UnsavedFile> =
        coroutineScope {
            val modified = tabs.filter { it.document?.savable() == true }.distinctBy { it.document }
            val saves = modified.map { tab -> async { saveNow(tab, timeoutMillis) } }
            saves.awaitAll().filterNotNull()
        }

    // Saves [tab] after the save of its document already running, if any; why it could not, or null once saved.
    private suspend fun saveNow(tab: EditorTab, timeoutMillis: Long): UnsavedFile? {
        val document = tab.document ?: return null
        val queue = queueOf(document)
        return try {
            val done = withTimeoutOrNull(timeoutMillis) { queue.mutex.withLock { write(tab) } }
            if (done == null) UnsavedFile(tab.entry, tab.relativePath, "the server did not answer in time") else null
        } catch (failed: IOException) {
            UnsavedFile(tab.entry, tab.relativePath, failed.message ?: failed::class.simpleName.orEmpty())
        }
    }

    private suspend fun write(tab: EditorTab) {
        val document = tab.document
        if (document != null && document.savable()) {
            val size = Attributes.of(Attribute.Size to sizeBucket(document.lineCount.toLong()))
            ClientTelemetry.measureToFrame(Operation.EditorSave, languageOf(tab) + size) {
                val editVersion = document.version
                try {
                    val writtenVersion = writtenOnce(tab.entry, document.text(), document.diskVersion)
                    // The version the disk now holds, even when the user typed meanwhile: the watch reports this
                    // write too, and the next comparison sees it as this save's work, not as another program's.
                    if (writtenVersion != null) document.diskVersion = writtenVersion
                } catch (refused: FileChangedException) {
                    // The server wrote nothing: another program changed the file since it was read, and both it
                    // and these edits are someone's content. The user chooses between them (see `DiskBanner`).
                    document.diskConflict = true
                    throw refused
                } catch (failed: IOException) {
                    document.saveError = failed.message ?: failed::class.simpleName.orEmpty()
                    throw failed
                }
                document.saveError = null
                // The disk now holds exactly these bytes, and both its version and these edits are answered: a
                // conflict seen while this save was on its way is over — the user's content is the disk's now.
                document.diskConflict = false
                if (document.version == editVersion) document.markSaved()
                onSaved(tab.entry)
            }
        }
    }

    // Writes [text], checked against the [expected] version of the file: refused when another program changed the
    // file since ([FileChangedException], nothing written), idempotent when it replayed a write that landed. A
    // server that restarted before answering may or may not have written it: it is asked once again instead of
    // being read back — with the same [expected] version, the write already on the disk succeeds (the bytes are
    // exactly the ones it holds), and one not there runs as a normal save.
    private suspend fun writtenOnce(entry: FileEntry, text: String, expected: FileVersion?): FileVersion? =
        try {
            fileSystem.write(entry, text, expected)
        } catch (unknown: OutcomeUnknownException) {
            replay(entry, text, expected, unknown)
        }

    // [writtenOnce]'s second attempt, carrying [first] along if it also fails (the server is still out of reach).
    private suspend fun replay(
        entry: FileEntry,
        text: String,
        expected: FileVersion?,
        first: OutcomeUnknownException,
    ): FileVersion? =
        try {
            fileSystem.write(entry, text, expected)
        } catch (failed: IOException) {
            first.addSuppressed(failed)
            throw first
        }

    private suspend fun CoroutineScope.open(tab: EditorTab, content: FileContent.Text): Document {
        val language = Language.fromFileName(tab.entry.name)
        val document =
            withContext(Dispatchers.Default) {
                Document(content.lines, language, content.lineSeparator).also {
                    // What the disk held when these lines were read: a save carried it, a change on disk answers it.
                    it.diskVersion = content.version
                    it.commentStates = SyntaxHighlighter.blockCommentStates(content.lines, language)
                    it.folding.regions = FoldingModel.computeRegions(content.lines, language)
                }
            }
        if (language.slashComments) launch { recomputeOnEdit(document, language) }
        launch { followChanges(tab, document) }
        val spec = GrammarSpec.forFileName(tab.entry.name)
        if (grammars != null && spec != null) {
            val session = SyntaxSession(spec)
            document.syntax = session
            launch { session.run(document, grammars) }
        }
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

// A conflict waits for the user: saving would silently drop what another program wrote.
private fun Document.savable(): Boolean = isModified && !diskConflict

private fun languageOf(tab: EditorTab): Attributes {
    val language = Language.fromFileName(tab.entry.name)
    return Attributes.of(Attribute.Language to language.name.lowercase())
}

// The size of what a tab shows, in lines; nothing when it shows no text.
private fun sizeOf(status: TabStatus): Attributes {
    val lines =
        when (status) {
            is TabStatus.Ready -> status.document.lineCount
            is TabStatus.Diff -> status.lines.size
            else -> return Attributes.of(Attribute.Result to "empty")
        }
    return linesAttributes(lines)
}

private fun linesAttributes(lines: Int): Attributes = Attributes.of(Attribute.Size to sizeBucket(lines.toLong()))
