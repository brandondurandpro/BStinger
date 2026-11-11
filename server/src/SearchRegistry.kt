package org.example.kotlineditor.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.toJson
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Find in Files streams of a session, keyed by the id of the request that started them (like
 * the terminals of a session). Each one launches a search next to the files, pushes its batches as they are
 * found and keeps them: a client that reconnects asks for [resume], which receives the batches after the last
 * sequence number it got, and the end of the stream when the search already ended. A stream that is gone (the
 * server restarted, its sessions with it) answers nothing, and the client runs its search again.
 */
internal class SearchRegistry(private val scope: CoroutineScope, private val push: (Frame) -> Unit) {
    private val searches = ConcurrentHashMap<Long, ServerSearch>()

    /** Starts the search of [search] as the stream [id]; the one this id had before (never in practice) stops. */
    fun start(id: Long, search: RipgrepSearch, root: FileEntry, options: FindOptions, limit: Int) {
        start(id, root, options, limit) { search.searchBatches(root, options, limit) }
    }

    /** Starts [batches] as the stream [id]: the source of the batches, for the tests. */
    fun start(id: Long, root: FileEntry, options: FindOptions, limit: Int, batches: () -> Flow<SearchBatch>) {
        stop(id)
        searches[id] = ServerSearch(id, scope, push, root, options, limit, batches)
    }

    fun stop(id: Long) {
        searches.remove(id)?.cancel()
    }

    fun stopAll() {
        searches.values.forEach { it.cancel() }
        searches.clear()
    }

    /** The batches of the stream [id] after [lastSeq], then its end; nothing when the stream is already gone. */
    fun resume(id: Long, lastSeq: Long) {
        searches[id]?.resume(lastSeq)
    }
}

/**
 * One streamed Find in Files: the search flow runs in the session's scope, each batch is pushed as an event
 * (and buffered for the reconnections), the last one is followed by an end frame carrying its sequence number.
 */
private class ServerSearch(
    private val id: Long,
    scope: CoroutineScope,
    private val push: (Frame) -> Unit,
    root: FileEntry,
    options: FindOptions,
    limit: Int,
    batches: () -> Flow<SearchBatch>,
) {
    private val lock = Any()

    // The batches buffered, in the order of their seq: a resume replays older before newer, since the client
    // drops everything up to its last known sequence number.
    private val events = TreeMap<Long, Frame>()
    private var endFrame: Frame? = null

    // Cancelled by [cancel] (the session is over); the search flow stops ripgrep or the local search with it.
    private val job: Job

    init {
        job =
            scope.launch {
                try {
                    batches().collect { batch -> publish(batch) }
                } catch (expected: CancellationException) {
                    throw expected
                } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
                    // A search that cannot run at all (a bug of the server) still ends its stream, with its reason.
                    val error = failure.message ?: failure::class.simpleName.orEmpty()
                    publish(failureBatch(error))
                }
                finish()
            }
    }

    fun cancel() = job.cancel()

    /** Replays the batches after [lastSeq], then the end frame; the client asks this after a reconnection. */
    fun resume(lastSeq: Long) {
        val replay =
            synchronized(lock) {
                val afterLast = events.tailMap(lastSeq).entries.toList()
                if (afterLast.firstOrNull()?.key == lastSeq) afterLast.drop(1) else afterLast
            }
        replay.forEach { push(it.value) }
        synchronized(lock) { endFrame }?.let(push)
    }

    // One batch more, buffered and pushed; after the end frame, batch events are dropped like duplicates.
    private fun publish(batch: SearchBatch) {
        val frame =
            Frame(
                FrameKind.Event,
                id,
                buildJsonObject {
                    put("seq", batch.seq)
                    put("batch", batch.toJson())
                },
            )
        synchronized(lock) {
            if (endFrame != null) return
            events[batch.seq] = frame
        }
        push(frame)
    }

    private fun failureBatch(error: String): SearchBatch {
        val nextSeq = synchronized(lock) { (events.keys.maxOrNull() ?: 0L) + 1 }
        return SearchBatch(nextSeq, emptyList(), done = true, total = 0, engine = null, error = error)
    }

    private fun finish() {
        synchronized(lock) {
            if (endFrame != null) return
            val last = events.keys.maxOrNull() ?: 0L
            endFrame = Frame(FrameKind.End, id, buildJsonObject { put("seq", last) })
        }
        val end = synchronized(lock) { endFrame }
        end?.let(push)
    }
}
