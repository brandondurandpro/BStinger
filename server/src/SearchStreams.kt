package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.SearchUpdate
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.matchesJson
import org.example.kotlineditor.rpc.toJson
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** A stream the server pushes to a client: it can replay what a reconnected client missed, and be stopped. */
internal interface ServerStream {
    /** Pushes again what followed the event [lastSeq] (the last one the client received), and the end if it came. */
    fun resume(lastSeq: Long)

    /** Stops the stream: the client no longer listens. */
    fun close()
}

/**
 * One streamed Find in Files: every batch of matches is pushed as a numbered event and kept, so a client that
 * reconnects gets exactly what it missed (at most the result limit is kept, until the client closes the stream).
 */
internal class SearchStream(private val id: Long, private val push: (Frame) -> Unit) : ServerStream {
    private val lock = Any()
    private val events = ArrayList<Frame>()
    private var end: Frame? = null
    private var job: Job? = null

    /** Collects [updates] in [scope] and pushes them. */
    fun start(scope: CoroutineScope, updates: Flow<SearchUpdate>) {
        val search =
            scope.launch(start = CoroutineStart.LAZY) {
                try {
                    updates.collect { update ->
                        when (update) {
                            is SearchUpdate.Found -> publish(update)
                            is SearchUpdate.Finished -> finish(update.toJson())
                        }
                    }
                    finish(SearchUpdate.Finished(SearchEngine.Ripgrep, complete = false).toJson())
                } catch (e: IOException) {
                    fail(e)
                } catch (e: IllegalArgumentException) {
                    fail(e)
                } catch (e: IllegalStateException) {
                    fail(e)
                }
            }
        synchronized(lock) { job = search }
        search.start()
    }

    override fun resume(lastSeq: Long) {
        synchronized(lock) {
            events.drop(lastSeq.toInt().coerceIn(0, events.size)).forEach(push)
            end?.let(push)
        }
    }

    override fun close() {
        synchronized(lock) { job }?.cancel()
    }

    private fun publish(found: SearchUpdate.Found) {
        synchronized(lock) {
            if (end != null) return
            val seq = events.size + 1L
            val event =
                Frame(
                    FrameKind.Event,
                    id,
                    buildJsonObject {
                        put("seq", seq)
                        put("matches", found.matches.matchesJson())
                    },
                )
            events += event
            push(event)
        }
    }

    // Only the first end counts (a search sends its own, the default one after it changes nothing).
    private fun finish(payload: JsonObject) {
        synchronized(lock) {
            if (end != null) return
            val frame = Frame(FrameKind.End, id, JsonObject(payload + ("seq" to JsonPrimitive(events.size))))
            end = frame
            push(frame)
        }
    }

    private fun fail(error: Exception) {
        finish(buildJsonObject { put("error", error.message ?: error::class.simpleName.orEmpty()) })
    }
}

/** The searches of a session, keyed by the id of their stream (the id of the request that started them). */
internal class SearchStreams(private val scope: CoroutineScope, private val push: (Frame) -> Unit) {
    private val searches = ConcurrentHashMap<Long, SearchStream>()

    /** Starts the search [id] unless it already runs (a retried request starts nothing). */
    fun start(id: Long, updates: () -> Flow<SearchUpdate>) {
        val stream = SearchStream(id, push)
        if (searches.putIfAbsent(id, stream) == null) stream.start(scope, updates())
    }

    operator fun get(id: Long): SearchStream? = searches[id]

    fun close(id: Long) {
        searches.remove(id)?.close()
    }

    fun closeAll() {
        searches.values.forEach { it.close() }
        searches.clear()
    }
}
