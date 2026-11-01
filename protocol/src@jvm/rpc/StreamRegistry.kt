package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/** How the events of a stream relate to each other, which decides what a gap in their sequence numbers means. */
enum class StreamKind {
    /**
     * Each event describes the whole state (a terminal screen): a newer event replaces the older ones, so events
     * missed during a disconnection do not matter and a late or duplicate one is dropped.
     */
    Replacing,

    /**
     * Each event adds to the previous ones (a batch of search results): they are delivered exactly once and in
     * order. An event after a gap is dropped; the server replays it with the missing ones on `stream/resume`.
     */
    Appending,
}

/** What a stream delivers: its events, then its end (both are the payloads sent by the server, without `seq`). */
sealed interface StreamMessage {
    /** One event. */
    class Event(
        /** What the server sent with the event. */
        val payload: JsonObject,
    ) : StreamMessage

    /** The end of the stream, delivered once every event before it was. */
    class End(
        /** What the server sent with the end. */
        val payload: JsonObject,
    ) : StreamMessage
}

/** The server restarted and lost a stream the client was listening to: what it would have pushed is gone. */
class StreamLostException : IOException("The server lost this stream (it restarted)")

/**
 * Server-pushed streams. Each event carries its sequence number (from 1); duplicates are dropped, and what a gap
 * means depends on the [StreamKind]. After a reconnection, [positions] tells the server where to resume each stream.
 */
internal class StreamRegistry {
    private val streams = ConcurrentHashMap<Long, StreamState>()

    private class StreamState(val kind: StreamKind) {
        val channel = Channel<StreamMessage>(Channel.UNLIMITED)
        var lastSeq = 0L
        var end: JsonObject? = null
        var endSeq = Long.MAX_VALUE
    }

    fun open(id: Long, kind: StreamKind): ReceiveChannel<StreamMessage> {
        val state = StreamState(kind)
        streams[id] = state
        return state.channel
    }

    fun close(id: Long) {
        streams.remove(id)?.channel?.close()
    }

    fun closeAll() {
        streams.values.forEach { it.channel.close() }
        streams.clear()
    }

    /** Last sequence number received by each open stream, to ask the server for what was missed. */
    fun positions(): Map<Long, Long> = streams.mapValues { synchronized(it.value) { it.value.lastSeq } }

    /**
     * The server restarted: its streams are gone for good. A terminal simply ends; a stream of results fails with
     * [StreamLostException], so that what it delivered is not taken for a complete answer.
     */
    fun loseAll() {
        streams.values.forEach { stream ->
            stream.channel.close(if (stream.kind == StreamKind.Appending) StreamLostException() else null)
        }
        streams.clear()
    }

    fun onEvent(id: Long, payload: JsonElement) {
        val stream = streams[id] ?: return
        val event = payload.jsonObject
        val seq = event.long(SEQ)
        synchronized(stream) {
            val accepted =
                when (stream.kind) {
                    StreamKind.Replacing -> seq > stream.lastSeq
                    StreamKind.Appending -> seq == stream.lastSeq + 1
                }
            if (accepted) {
                stream.lastSeq = seq
                stream.channel.trySend(StreamMessage.Event(JsonObject(event - SEQ)))
            }
            endIfDone(id, stream)
        }
    }

    fun onEnd(id: Long, payload: JsonElement) {
        val stream = streams[id] ?: return
        val end = payload.jsonObject
        synchronized(stream) {
            stream.endSeq = end.long(SEQ)
            stream.end = JsonObject(end - SEQ)
            endIfDone(id, stream)
        }
    }

    private fun endIfDone(id: Long, stream: StreamState) {
        val end = stream.end
        if (end != null && stream.lastSeq >= stream.endSeq) {
            stream.channel.trySend(StreamMessage.End(end))
            stream.channel.close()
            streams.remove(id)
        }
    }

    private companion object {
        const val SEQ = "seq"
    }
}
