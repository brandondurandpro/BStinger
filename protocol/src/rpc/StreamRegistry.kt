package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import org.example.kotlineditor.model.RunChunk
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.TerminalSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * The server-pushed streams this client has open: the screens of terminals, the batches of streamed Finds in
 * Files and the chunks of runs. Each event carries the sequence number of what it holds, so duplicates are dropped and a stream whose
 * events arrive after a gap simply continues with the next one (`positions()` and `loseAll()` drive the replay of
 * what was missed).
 */
internal class StreamRegistry {
    private val streams = ConcurrentHashMap<Long, Stream>()

    private sealed class Stream {
        var lastSeq = 0L
        var endSeq = Long.MAX_VALUE

        abstract fun close()
    }

    private class TerminalStream(val channel: Channel<TerminalSnapshot>) : Stream() {
        override fun close() {
            channel.close()
        }
    }

    private class SearchStream(val channel: Channel<SearchBatch>) : Stream() {
        override fun close() {
            channel.close()
        }
    }

    private class RunStream(val channel: Channel<RunChunk>) : Stream() {
        override fun close() {
            channel.close()
        }
    }

    /** What a stream carries. */
    enum class Kind {
        /** The screens of a terminal. */
        Terminal,

        /** The batches of a Find in Files. */
        Search,

        /** The chunks of a run. */
        Run,
    }

    /** A stream the client has to replay after a reconnection: which kind it is, and the last seq received. */
    class Resume(
        /** Id of the stream. */
        val id: Long,
        /** What the stream carries, which tells the method that resumes it. */
        val kind: Kind,
        /** Last sequence number received: the server replays what comes after it. */
        val lastSeq: Long,
    )

    /** Registers a stream of terminal screens, before the request that starts it is sent. */
    fun open(id: Long): Channel<TerminalSnapshot> {
        val channel = Channel<TerminalSnapshot>(Channel.UNLIMITED)
        streams[id] = TerminalStream(channel)
        return channel
    }

    /** Registers a stream of Find in Files batches, before the request that starts it is sent. */
    fun openSearch(id: Long): Channel<SearchBatch> {
        val channel = Channel<SearchBatch>(Channel.UNLIMITED)
        streams[id] = SearchStream(channel)
        return channel
    }

    /** Registers a stream of run chunks, before the request that starts it is sent. */
    fun openRun(id: Long): Channel<RunChunk> {
        val channel = Channel<RunChunk>(Channel.UNLIMITED)
        streams[id] = RunStream(channel)
        return channel
    }

    /** Stops listening to the stream [id]. */
    fun close(id: Long) {
        streams.remove(id)?.close()
    }

    fun closeAll() {
        streams.values.forEach { it.close() }
        streams.clear()
    }

    /** What each open stream still has to receive, to ask the server for what was missed. */
    fun resumes(): List<Resume> = streams.map { (id, stream) -> Resume(id, kindOf(stream), stream.lastSeq) }

    private fun kindOf(stream: Stream): Kind =
        when (stream) {
            is SearchStream -> Kind.Search
            is RunStream -> Kind.Run
            is TerminalStream -> Kind.Terminal
        }

    /** The server restarted: its streams are gone for good. */
    fun loseAll() = closeAll()

    fun onEvent(id: Long, payload: JsonElement) {
        val stream = streams[id] ?: return
        val event = payload.jsonObject
        val seq = event.long("seq")
        // The kinds are told apart by what they carry: a screen for a terminal, a batch for a search, a chunk for a run.
        val batch = event[BATCH]?.let { it.searchBatch() }
        val chunk = event[CHUNK]?.let { it.runChunk() }
        when {
            batch != null -> onBatch(id, stream as SearchStream, seq, batch)
            chunk != null -> onChunk(id, stream as RunStream, seq, chunk)
            else -> onSnapshot(id, stream as TerminalStream, seq, event.field("snapshot").snapshot())
        }
    }

    fun onEnd(id: Long, payload: JsonElement) {
        val stream = streams[id] ?: return
        synchronized(stream) {
            stream.endSeq = payload.jsonObject.long("seq")
            endIfDone(id, stream)
        }
    }

    private fun onSnapshot(id: Long, stream: TerminalStream, seq: Long, snapshot: TerminalSnapshot) {
        synchronized(stream) {
            // Every snapshot describes the whole screen: the last one received is what is shown.
            if (seq > stream.lastSeq) {
                stream.lastSeq = seq
                stream.channel.trySend(snapshot)
            }
            endIfDone(id, stream)
        }
    }

    private fun onBatch(id: Long, stream: SearchStream, seq: Long, batch: SearchBatch) {
        synchronized(stream) {
            // The batches build one result: letting an older one through would repeat (or lose) hits.
            if (seq > stream.lastSeq) {
                stream.lastSeq = seq
                stream.channel.trySend(batch)
            }
            endIfDone(id, stream)
        }
    }

    private fun onChunk(id: Long, stream: RunStream, seq: Long, chunk: RunChunk) {
        synchronized(stream) {
            // The chunks build one output: letting an older one through would repeat (or lose) lines.
            if (seq > stream.lastSeq) {
                stream.lastSeq = seq
                stream.channel.trySend(chunk)
            }
            endIfDone(id, stream)
        }
    }

    private fun endIfDone(id: Long, stream: Stream) {
        if (stream.lastSeq >= stream.endSeq) {
            stream.close()
            streams.remove(id)
        }
    }

    private companion object {
        private const val BATCH = "batch"
        private const val CHUNK = "chunk"
    }
}
