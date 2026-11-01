package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.SearchUpdate
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
import kotlin.time.Duration.Companion.milliseconds

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

    /**
     * Streams ripgrep's matches in batches ([BATCH_SIZE] at most, sent at most [BATCH_DELAY] after their first
     * match). When ripgrep is missing, or fails before finding anything, the built-in search answers instead; when
     * it fails later, the matches already sent stand and the search ends as incomplete. On timeout, the matches
     * found so far are the answer.
     */
    override fun findInFiles(root: FileEntry, query: SearchQuery, limit: Int): Flow<SearchUpdate> =
        flow {
            val executable = if (query.text.isEmpty() || limit <= 0) null else ripgrep()
            val outcome = executable?.let { search(it, root, query, limit) }
            val failedEarly = outcome?.failed == true && outcome.found == 0
            if (outcome == null || failedEarly) {
                emitAll(fallback.findInFiles(root, query, limit))
            } else {
                emit(SearchUpdate.Finished(SearchEngine.Ripgrep, outcome.complete))
            }
        }

    /** How a ripgrep search went. */
    private class Outcome {
        var found = 0
        var truncated = false
        var failed = false
        var timedOut = false
        val complete: Boolean get() = !failed && !truncated && !timedOut
    }

    // Sends the batches of matches ripgrep finds, and tells how it went.
    private suspend fun FlowCollector<SearchUpdate>.search(
        executable: RipgrepBinary,
        root: FileEntry,
        query: SearchQuery,
        limit: Int,
    ): Outcome {
        val outcome = Outcome()
        val runner = RipgrepRunner(executable.path, maxFileSize, onStart = onStart)
        val batches =
            runner
                .matches(RipgrepQuery(Paths.get(root.path), query.text, query.ignoreCase))
                .map { it.toTextMatch() }
                .take(limit + 1) // one more than the limit tells that the results were cut
                .batched(BATCH_SIZE, BATCH_DELAY)
                .catch { failure -> if (failure is IOException) outcome.failed = true else throw failure }
        val finished =
            withTimeoutOrNull(timeout) {
                batches.collect { batch ->
                    val kept = batch.take(limit - outcome.found)
                    outcome.truncated = outcome.truncated || kept.size < batch.size
                    outcome.found += kept.size
                    if (kept.isNotEmpty()) emit(SearchUpdate.Found(kept))
                }
            }
        outcome.timedOut = finished == null
        return outcome
    }

    private fun RipgrepMatch.toTextMatch(): TextMatch {
        val file = Paths.get(path)
        val entry = FileEntry(file.toString(), file.fileName?.toString() ?: path, false)
        return TextMatch(entry, line, ranges.firstOrNull()?.first ?: 0, text.take(MAX_LINE_PREVIEW))
    }

    private companion object {
        /** Same preview length as the built-in search. */
        const val MAX_LINE_PREVIEW = 500

        /** Most matches sent in one message. */
        const val BATCH_SIZE = 200

        /** Longest a found match waits for others before being sent. */
        val BATCH_DELAY = 30.milliseconds
    }
}
