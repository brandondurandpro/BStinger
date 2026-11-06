package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepMatch
import org.example.kotlineditor.server.ripgrep.RipgrepProvisioner
import org.example.kotlineditor.server.ripgrep.RipgrepQuery
import org.example.kotlineditor.server.ripgrep.RipgrepRunner
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import java.io.IOException
import java.nio.file.Paths
import kotlin.time.Duration

/**
 * [ProjectSearch] whose Find in Files runs ripgrep, like VS Code: native, parallel, no index, and it honours
 * `.gitignore`. The other queries, and Find in Files whenever ripgrep is missing or fails, use [fallback].
 */
class RipgrepSearch internal constructor(
    private val fallback: LocalSearch,
    private val provisioner: RipgrepProvisioner,
    private val maxFileSize: Long,
    private val timeout: Duration,
    private val onStart: (ProcessHandle) -> Unit,
) : ProjectSearch by fallback {
    private val lookup = Mutex()
    private var located = false
    private var binary: RipgrepBinary? = null

    /** Search with ripgrep found as [settings] say; files larger than [maxFileSize] are not searched. */
    constructor(
        fallback: LocalSearch,
        settings: RipgrepSettings = RipgrepSettings(),
        maxFileSize: Long = TextFiles.DEFAULT_MAX_SIZE,
    ) : this(fallback, RipgrepProvisioner(settings), maxFileSize, settings.timeout, {})

    /**
     * The ripgrep executable in use, looked up (and downloaded if needed) on the first call only; null when the
     * built-in search is used instead.
     */
    suspend fun ripgrep(): RipgrepBinary? =
        lookup.withLock {
            if (!located) {
                binary = withContext(Dispatchers.IO) { provisioner.locate() }
                located = true
            }
            binary
        }

    override suspend fun findInFiles(
        root: FileEntry,
        query: String,
        ignoreCase: Boolean,
        limit: Int,
    ): List<TextMatch> = findInFiles(root, FindOptions(text = query, ignoreCase = ignoreCase), limit)

    override suspend fun findInFiles(root: FileEntry, options: FindOptions, limit: Int): List<TextMatch> {
        val executable =
            if (options.text.isEmpty() || limit <= 0) null else ripgrep()
        return executable?.let { search(it, root, options, limit) } ?: fallback.findInFiles(root, options, limit)
    }

    /**
     * The results of a Find in Files, streamed in [SearchBatch]es as they are found: the client sees them while
     * the search is still reading files. The last batch of a search closes it with the engine that ran it (and
     * with its error, when the search failed); the batches are numbered from 1, since the keeper that pushes them
     * buffers them and replays the ones a client missed.
     *
     * On failure of ripgrep when it found nothing (unavailable, or it refused the query), the built-in engine runs
     * the same search through [fallback], in the same batches: the client does not have to know which one ran.
     * When ripgrep stopped after having found matches, the search closes with what it found and an error:
     * replaying the built-in search would duplicate the hits that were sent.
     */
    fun searchBatches(root: FileEntry, options: FindOptions, limit: Int): Flow<SearchBatch> = flow {
        if (options.text.isEmpty() || limit <= 0) {
            emit(SearchBatch(1, emptyList(), done = true, total = 0, engine = SearchEngine.BuiltIn))
            return@flow
        }
        val executable = ripgrep()
        if (executable == null) {
            fromLocal(root, options, limit).collect { emit(it) }
            return@flow
        }
        var sequence = 0L
        var delivered = 0
        val succeeded =
            try {
                val counting =
                    ripgrepBatches(executable, root, options, limit).onEach { batch ->
                        sequence = batch.seq
                        delivered = batch.total
                    }
                counting.collect { emit(it) }
                true
            } catch (expected: IOException) {
                false
            }
        if (succeeded) return@flow
        if (delivered == 0) {
            fromLocal(root, options, limit).collect { emit(it) }
        } else {
            sequence++
            val error = "ripgrep stopped searching after $delivered matches, before finding the rest"
            emit(SearchBatch(sequence, emptyList(), done = true, total = delivered, engine = null, error = error))
        }
    }

    // The built-in engine, in the same batches as the streamed one: the sequence numbers, the engine of the batch
    // that closes the search, and an error batch for a query it cannot run (an invalid regular expression).
    private fun fromLocal(root: FileEntry, options: FindOptions, limit: Int): Flow<SearchBatch> = flow {
        var sequence = 0L
        var total = 0
        try {
            fallback.matchBatches(root, options, limit).collect { matches ->
                sequence++
                total += matches.size
                emit(SearchBatch(sequence, matches, done = false, total = total, engine = null))
            }
        } catch (failure: IllegalArgumentException) {
            val error = failure.message ?: failure::class.simpleName.orEmpty()
            sequence++
            emit(SearchBatch(sequence, emptyList(), done = true, total = total, engine = null, error = error))
            return@flow
        }
        sequence++
        emit(SearchBatch(sequence, emptyList(), done = true, total = total, engine = SearchEngine.BuiltIn))
    }

    // ripgrep's matches, in batches of [BATCH_HITS] hits or every [BATCH_INTERVAL] (whichever comes first), then
    // the batch that closes the search with its total. Fails with the error of a query ripgrep refuses to run.
    private fun ripgrepBatches(
        executable: RipgrepBinary,
        root: FileEntry,
        options: FindOptions,
        limit: Int,
    ): Flow<SearchBatch> = channelFlow {
        val inbox = Channel<RipgrepMatch>(INBOX)
        val pump =
            launch {
                val runner = RipgrepRunner(executable.path, maxFileSize, onStart = onStart)
                try {
                    runner.matches(queryOf(root, options)).take(limit).collect { inbox.send(it) }
                    inbox.close()
                } catch (expected: IOException) {
                    // ripgrep could not run the search: the batching fails with the reason it gives.
                    inbox.close(expected)
                }
            }
        val pending = ArrayList<TextMatch>()
        var sequence = 0L
        var total = 0
        var nextBatch = System.nanoTime() + BATCH_INTERVAL_NANOS

        // Flushes the pending hits (the batch is full, or its delay expired) and arms the next delay.
        suspend fun flushPending() {
            sequence++
            send(SearchBatch(sequence, pending.toList(), done = false, total = total, engine = null))
            pending.clear()
            nextBatch = System.nanoTime() + BATCH_INTERVAL_NANOS
        }

        while (true) {
            val dueIn = nextBatch - System.nanoTime()
            val reception =
                if (dueIn > 0) {
                    withTimeoutOrNull(dueIn) { inbox.receiveCatching() }
                } else {
                    null
                }
            val match = reception?.getOrNull()
            if (match != null) {
                pending += match.toTextMatch()
                total++
                if (pending.size >= BATCH_HITS) flushPending()
                continue
            }
            when {
                reception == null -> if (pending.isNotEmpty()) flushPending()

                reception.isClosed -> {
                    if (pending.isNotEmpty()) flushPending()
                    val cause = reception.exceptionOrNull()
                    if (cause != null) throw cause
                    break
                }

                else -> Unit
            }
        }
        send(SearchBatch(sequence + 1, emptyList(), done = true, total = total, engine = SearchEngine.Ripgrep))
    }

    private fun queryOf(root: FileEntry, options: FindOptions) =
        RipgrepQuery(
            root = Paths.get(root.path),
            text = options.text,
            ignoreCase = options.ignoreCase,
            wholeWord = options.wholeWord,
            regex = options.regex,
            filePattern = options.filePattern,
            scope = options.scope,
            includeIgnored = options.includeIgnored,
        )

    // Null when ripgrep failed: the built-in search takes over.
    private suspend fun search(
        executable: RipgrepBinary,
        root: FileEntry,
        options: FindOptions,
        limit: Int,
    ): List<TextMatch>? =
        try {
            val runner = RipgrepRunner(executable.path, maxFileSize, onStart = onStart)
            val hits = ArrayList<TextMatch>()
            // On timeout, the lines found so far are the answer.
            withTimeoutOrNull(timeout) {
                runner.matches(queryOf(root, options)).take(limit).collect { hits += it.toTextMatch() }
            }
            // ripgrep searches files in parallel: sorting keeps the order stable from one search to the next.
            hits.sortedWith(compareBy<TextMatch> { it.entry.path }.thenBy { it.line })
        } catch (_: IOException) {
            null
        }

    private fun RipgrepMatch.toTextMatch(): TextMatch {
        val file = Paths.get(path)
        val entry = FileEntry(file.toString(), file.fileName?.toString() ?: path, false)
        return TextMatch(entry, line, ranges.firstOrNull()?.first ?: 0, text.take(MAX_LINE_PREVIEW))
    }

    private companion object {
        /** Same preview length as the built-in search. */
        const val MAX_LINE_PREVIEW = 500

        /** Hits that fill one batch of the streamed Find in Files. */
        const val BATCH_HITS = 32

        /** Longest a batch waits to fill up: whichever comes first between [BATCH_HITS] and [BATCH_INTERVAL]. */
        const val BATCH_INTERVAL_NANOS = 50L * 1_000_000

        /** ripgrep matches buffered between the pump and the batching, a few batches' worth. */
        const val INBOX = 64
    }
}
