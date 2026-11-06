package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.telemetry.Span
import org.example.kotlineditor.telemetry.endAtFrame
import org.example.kotlineditor.telemetry.sizeOf
import java.io.IOException

/** A server's stream that failed is started again, once; beyond it the blocking search keeps working. */
private const val RESTARTS = 1

private val RESULTS_FIRST = compareBy<SearchHit> { it.entry.path }.thenBy { it.line }

/**
 * Find in Files of a project, streamed by the server and shown as its results arrive. A batch lost after a
 * reconnection is replayed by the server on its own; a search lost when the server restarted runs again, once;
 * a stream that does not run falls back to the blocking search, so the feature keeps working. Which engine ran
 * ([engine]) and the end ([finished]) end up in the popup, along why a search cannot run ([error]).
 */
@Stable
class FindInFilesSearch(
    private val root: FileEntry,
    private val search: ProjectSearch,
    private val fileIndex: FileIndex,
    private val scope: CoroutineScope,
) {
    /** The hits found so far: the batches of the streamed search build one result below the popup. */
    var results: List<SearchHit> by mutableStateOf(emptyList())
        private set

    /** Whether the search is over. */
    var finished: Boolean by mutableStateOf(true)
        private set

    /** Engine that ran the search, known once [finished]; null when the popup cannot tell (the fallback). */
    var engine: SearchEngine? by mutableStateOf(null)
        private set

    /** Why the search could not run (an invalid regular expression...), as the engines explained them. */
    var error: String? by mutableStateOf(null)
        private set

    private var job: Job? = null

    // The span of the running search, ended once its results reached the screen (like a terminal's start one).
    private var span: Span? = null

    // Identifies the state a search updates: a batch of a cancelled old one does not update anything.
    private var generation = 0

    /** Runs [options] as its own search, cancelling the running one: every change of options restarts. */
    fun start(options: FindOptions) {
        closeRunning()
        generation++
        val token = generation
        job?.cancel()
        job = scope.launch { run(options, token) }
    }

    /**
     * One search, from typing to its last batch: streamed, with the server's replay, the restart and the
     * blocking search as the fallbacks. An empty text ends at once with no match, like the plain search did.
     */
    private suspend fun run(options: FindOptions, token: Int, restarts: Int = RESTARTS) {
        begin()
        if (options.text.isEmpty()) return
        span = ClientTelemetry.start(Operation.SearchFindInFiles)
        val stream = search.findInFilesStream(root, options, DEFAULT_TEXT_HITS)
        if (stream == null) {
            blocking(options, token)
            return
        }
        streamSearch(stream.batches, token)
        if (generation != token || finished) return
        // The stream ended before its done batch: its search is gone (the server restarted, its streams with
        // it), and it runs again once; beyond that, or with a newer search running, the blocking one takes it.
        if (restarts > 0) {
            run(options, token, restarts = restarts - 1)
        } else {
            blocking(options, token)
        }
    }

    // The collect of one stream: over when its done batch arrives; over without one when the server lost it.
    private suspend fun streamSearch(batches: Flow<SearchBatch>, token: Int) {
        try {
            batches.collect { batch -> absorb(batch, token) }
        } catch (expected: CancellationException) {
            throw expected
        }
    }

    /** One more batch: more hits below the popup, then the end of the search with its engine and error. */
    private fun absorb(batch: SearchBatch, token: Int) {
        if (token != generation) return
        if (batch.hits.isNotEmpty()) {
            val more = batch.hits.map { hit ->
                SearchHit(hit.entry, fileIndex.relativePath(hit.entry), hit.line, hit.column, hit.lineText)
            }
            results = results + more
        }
        if (batch.error != null) error = batch.error
        if (!batch.done) return
        finished = true
        engine = batch.engine
        results = results.sortedWith(RESULTS_FIRST)
        endAtShown()
    }

    // The blocking search, what the popup ran before one streamed: without a stream (an old server), without a
    // server at all (an unreachable one), or after losing one stream after another.
    private suspend fun blocking(options: FindOptions, token: Int) {
        if (token != generation || finished) return
        try {
            results = fileIndex.findInFiles(options, DEFAULT_TEXT_HITS)
            engine = null
            finished = true
        } catch (expected: IllegalArgumentException) {
            // From the built-in engine only: a query it cannot compile (an invalid regular expression).
            error = expected.message ?: expected::class.simpleName.orEmpty()
            results = emptyList()
            finished = true
        } catch (expected: IOException) {
            // The server is unreachable: no results until it is back (the next search runs when typing does).
            results = emptyList()
            finished = true
        }
        endAtShown()
    }

    // No state of the replaced search left standing here: its hits, its engine, and its error all gone.
    private fun begin() {
        results = emptyList()
        finished = false
        engine = null
        error = null
    }

    // The search replaced while it was running (a keystroke, an option): its span ends now, nothing further.
    private fun closeRunning() {
        val open = span ?: return
        span = null
        ClientTelemetry.end(open, Outcome.Cancelled, attributes())
    }

    // Ends the span of the search once a frame has shown its results: what the search measured, in the end.
    private fun endAtShown() {
        val open = span ?: return
        span = null
        scope.launch { ClientTelemetry.endAtFrame(open, Outcome.Ok, attributes()) }
    }

    // What the results say about the search that ran: how many hits it found and whether it found any.
    private fun attributes() =
        sizeOf(results.size) + Attributes.of(Attribute.Result to if (results.isEmpty()) "empty" else "found")
}
