package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.SearchInterruptedException
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.SearchUpdate
import java.io.IOException

private val BY_FILE_THEN_LINE = compareBy<SearchHit> { it.relativePath }.thenBy { it.line }.thenBy { it.column }

/**
 * The results of the Find in Files popup, filled as the server streams them: they are shown from the first batch,
 * sorted by file then line whatever order they arrive in.
 */
@Stable
class FindInFilesSearch(private val index: FileIndex, private val connection: StateFlow<ConnectionStatus>) {
    /** Matches received so far, sorted. */
    var hits: List<SearchHit> by mutableStateOf(emptyList())
        private set

    /** Whether the search is still running (more matches may come). */
    var searching by mutableStateOf(false)
        private set

    /** How the last search ended, null while it runs or when there is none. */
    var finished: SearchUpdate.Finished? by mutableStateOf(null)
        private set

    /** Why the last search failed, null when it did not. */
    var error: String? by mutableStateOf(null)
        private set

    /** Number of distinct files among [hits]. */
    val fileCount: Int get() = hits.distinctBy { it.entry.path }.size

    /** Whether the server searched without ripgrep (missing or failing), which the popup tells. */
    val builtIn: Boolean get() = finished?.engine == SearchEngine.BuiltIn

    /**
     * Runs [query], replacing the previous results; returns when it is over. Cancelling the caller (a new query
     * typed, the popup closed) stops the search on the server. If the server restarts meanwhile, the search starts
     * again from scratch once it is back.
     */
    suspend fun run(query: SearchQuery) {
        hits = emptyList()
        finished = null
        error = null
        if (query.text.isEmpty()) {
            searching = false
            return
        }
        searching = true
        try {
            var interrupted = true
            while (interrupted) {
                interrupted =
                    try {
                        index.findInFiles(query).collect(::onUpdate)
                        false
                    } catch (_: SearchInterruptedException) {
                        hits = emptyList()
                        connection.first { it == ConnectionStatus.Connected }
                        true
                    }
            }
        } catch (e: IOException) {
            error = e.message ?: "Search failed"
        } finally {
            searching = false
        }
    }

    private fun onUpdate(update: SearchUpdate) {
        when (update) {
            is SearchUpdate.Found -> {
                val batch = update.matches.map(index::hitOf).sortedWith(BY_FILE_THEN_LINE)
                hits = merge(hits, batch)
            }

            is SearchUpdate.Finished -> {
                finished = update
            }
        }
    }

    // Both lists are sorted: a linear merge keeps adding a batch cheap however many results are shown.
    private fun merge(sorted: List<SearchHit>, batch: List<SearchHit>): List<SearchHit> {
        val merged = ArrayList<SearchHit>(sorted.size + batch.size)
        var i = 0
        var j = 0
        while (i < sorted.size && j < batch.size) {
            merged += if (BY_FILE_THEN_LINE.compare(sorted[i], batch[j]) <= 0) sorted[i++] else batch[j++]
        }
        while (i < sorted.size) merged += sorted[i++]
        while (j < batch.size) merged += batch[j++]
        return merged
    }
}
