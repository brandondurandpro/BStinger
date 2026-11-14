package org.example.kotlineditor.server

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.server.ripgrep.RipgrepBinary
import org.example.kotlineditor.server.ripgrep.RipgrepMatch
import org.example.kotlineditor.server.ripgrep.RipgrepQuery
import org.example.kotlineditor.server.ripgrep.RipgrepRunner
import java.io.IOException
import java.nio.file.Paths

/** What ripgrep must search, out of the options the client asked for. */
internal fun queryOf(root: FileEntry, options: FindOptions) =
    RipgrepQuery(
        root = Paths.get(root.path),
        text = options.text,
        ignoreCase = options.ignoreCase,
        wholeWord = options.wholeWord,
        regex = options.regex,
        filePattern = options.filePattern,
        scopes = options.scopes,
        includeIgnored = options.includeIgnored,
    )

/**
 * The results of a Find in Files, streamed in [SearchBatch]es as they are found: the client sees them while the
 * search is still reading files. The last batch of a search closes it with the engine that ran it (and with its
 * error, when the search failed); the batches are numbered from 1, since the keeper that pushes them buffers
 * them and replays the ones a client missed.
 *
 * On failure of ripgrep when it found nothing (unavailable, or it refused the query), the built-in engine runs
 * the same search through [fallback], in the same batches: the client does not have to know which one ran.
 * When ripgrep stopped after having found matches, the search closes with what it found and an error:
 * replaying the built-in search would duplicate the hits that were sent.
 */
internal class RipgrepStream(
    private val fallback: LocalSearch,
    private val maxFileSize: Long,
    private val onStart: (ProcessHandle) -> Unit,
    private val binary: suspend () -> RipgrepBinary?,
) {
    fun searchBatches(root: FileEntry, options: FindOptions, limit: Int): Flow<SearchBatch> =
        flow {
            val plain = options.text.isEmpty() || limit <= 0
            val executable = if (plain) null else binary()
            when {
                plain -> emit(SearchBatch(1, emptyList(), done = true, total = 0, engine = SearchEngine.BuiltIn))
                executable == null -> fromLocal(root, options, limit).collect { emit(it) }
                else -> streamFrom(executable, root, options, limit)
            }
        }

    // One ripgrep search, streamed: when its process died (an IOException) with nothing found, the built-in
    // engine runs the same search through [fromLocal], in the same batches; when it died after matches, the
    // search closes with what it found and an error: replaying there would duplicate the hits already sent.
    private suspend fun FlowCollector<SearchBatch>.streamFrom(
        executable: RipgrepBinary,
        root: FileEntry,
        options: FindOptions,
        limit: Int,
    ) {
        var sequence = 0L
        var delivered = 0
        val succeeded =
            try {
                val counted =
                    ripgrepBatches(executable, root, options, limit).onEach { batch ->
                        sequence = batch.seq
                        delivered = batch.total
                    }
                counted.collect { emit(it) }
                true
            } catch (expected: IOException) {
                false
            }
        if (succeeded) return
        if (delivered == 0) {
            fromLocal(root, options, limit).collect { emit(it) }
        } else {
            sequence++
            val error = "ripgrep stopped searching after $delivered matches, before finding the rest"
            emit(SearchBatch(sequence, emptyList(), done = true, total = delivered, engine = null, error = error))
        }
    }

    // The built-in engine, in the same batches as the streamed one: the sequence numbers, the engine of the
    // batch that closes the search, and an error batch for a query it cannot run (an invalid regex).
    private fun fromLocal(root: FileEntry, options: FindOptions, limit: Int): Flow<SearchBatch> =
        flow {
            var sequence = 0L
            var total = 0
            val failure =
                try {
                    fallback.matchBatches(root, options, limit).collect { matches ->
                        sequence++
                        total += matches.size
                        emit(SearchBatch(sequence, matches, done = false, total = total, engine = null))
                    }
                    null
                } catch (failure: IllegalArgumentException) {
                    failure.message ?: failure::class.simpleName.orEmpty()
                }
            sequence++
            val closing =
                if (failure == null) {
                    SearchBatch(sequence, emptyList(), done = true, total = total, engine = SearchEngine.BuiltIn)
                } else {
                    SearchBatch(sequence, emptyList(), done = true, total = total, engine = null, error = failure)
                }
            emit(closing)
        }

    // ripgrep's matches, in batches of [BATCH_HITS] hits or every [BATCH_INTERVAL] (whichever comes first), then
    // the batch that closes the search with its total. Fails with the error of a query ripgrep refuses to run.
    private fun ripgrepBatches(
        executable: RipgrepBinary,
        root: FileEntry,
        options: FindOptions,
        limit: Int,
    ): Flow<SearchBatch> =
        channelFlow {
            val inbox = Channel<RipgrepMatch>(INBOX)
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
            batchesOf(inbox)
        }

    // Sends the matches of one ripgrep run as they come: a batch closes on [BATCH_HITS] hits or when
    // [BATCH_INTERVAL] passed since the previous one, whichever first, and the batch that closes the search
    // closes the flow. The failure of ripgrep fails the search with it.
    private suspend fun ProducerScope<SearchBatch>.batchesOf(inbox: Channel<RipgrepMatch>) {
        val waiting = ArrayList<TextMatch>()
        var sequence = 0L
        var total = 0
        var nextBatch = System.nanoTime() + BATCH_INTERVAL_NANOS

        // A batch of the moment closes: what ripgrep found and waits is sent, and the delay of the next one armed.
        suspend fun closeBatch() {
            if (waiting.isNotEmpty()) {
                sequence++
                send(SearchBatch(sequence, waiting.toList(), done = false, total = total, engine = null))
                waiting.clear()
            }
            nextBatch = System.nanoTime() + BATCH_INTERVAL_NANOS
        }

        var running = true
        var failure: Throwable? = null
        while (running) {
            val dueIn = nextBatch - System.nanoTime()
            val reception =
                if (dueIn > 0) {
                    withTimeoutOrNull(dueIn) { inbox.receiveCatching() }
                } else {
                    inbox.receiveCatching()
                }
            val match = reception?.getOrNull()
            when {
                match != null -> {
                    waiting += match.toTextMatch(MAX_LINE_PREVIEW)
                    total++
                    if (waiting.size >= BATCH_HITS) closeBatch()
                }

                reception?.isClosed == true -> {
                    failure = reception.exceptionOrNull()
                    running = false
                }

                waiting.isNotEmpty() -> {
                    closeBatch() // the delay of the batch expired: what waits is sent now
                }
            }
        }
        closeBatch()
        // What ripgrep found is sent before its failure or the end: found matches are kept even when the run
        // failed, and what closes the search closes the flow with the engine that ran it.
        failure?.let { throw it }
        send(SearchBatch(sequence + 1, emptyList(), done = true, total = total, engine = SearchEngine.Ripgrep))
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
