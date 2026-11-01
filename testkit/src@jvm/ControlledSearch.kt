package org.example.kotlineditor.testkit

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.SearchUpdate
import java.util.concurrent.atomic.AtomicInteger

/**
 * The searches of a server, as a test controls them: each match of [delegate] is sent as its own batch, [pauseMs]
 * apart, and after [hold] a search sends its first batch then waits for [release]. It counts the searches started
 * and those stopped before their end, so a test sees whether a retried request ran twice or a closed popup stopped
 * its search.
 */
class ControlledSearch(private val delegate: ProjectSearch) : ProjectSearch by delegate {
    /** Searches started. */
    val started = AtomicInteger()

    /** Searches stopped before their end (the client closed the stream, or the server stopped). */
    val stopped = AtomicInteger()

    /** Pause before each batch. */
    @Volatile var pauseMs = 0L

    @Volatile private var gate: CompletableDeferred<Unit>? = null

    /** The next searches stop after their first batch, until [release]. */
    fun hold() {
        gate = CompletableDeferred()
    }

    /** Lets the held searches go on. */
    fun release() {
        gate?.complete(Unit)
    }

    override fun findInFiles(root: FileEntry, query: SearchQuery, limit: Int): Flow<SearchUpdate> =
        flow {
            started.incrementAndGet()
            val held = gate
            var sent = 0
            delegate.findInFiles(root, query, limit).collect { update ->
                when (update) {
                    is SearchUpdate.Found -> {
                        for (match in update.matches) {
                            if (sent == 1) held?.await()
                            send(SearchUpdate.Found(listOf(match)))
                            sent++
                        }
                    }

                    is SearchUpdate.Finished -> {
                        if (sent <= 1) held?.await()
                        emit(update)
                    }
                }
            }
        }.onCompletion { cause -> if (cause != null) stopped.incrementAndGet() }

    private suspend fun FlowCollector<SearchUpdate>.send(update: SearchUpdate) {
        if (pauseMs > 0) delay(pauseMs)
        emit(update)
    }
}
