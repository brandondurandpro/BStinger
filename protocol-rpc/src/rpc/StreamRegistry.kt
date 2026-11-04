package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-pushed terminal screens, decoded by the function each stream was opened with. Each event carries the
 * sequence number of the complete snapshot it holds: duplicates are dropped, and a snapshot that arrives after a gap
 * simply replaces the state, because every snapshot describes the whole screen.
 */
internal class StreamRegistry {
    private val streams = ConcurrentHashMap<Long, StreamState<*>>()

    private class StreamState<T>(val channel: Channel<T>, private val decode: (JsonElement) -> T) {
        var lastSeq = 0L
        var endSeq = Long.MAX_VALUE

        fun deliver(snapshot: JsonElement) {
            channel.trySend(decode(snapshot))
        }
    }

    fun <T> open(id: Long, decode: (JsonElement) -> T): Channel<T> {
        val channel = Channel<T>(Channel.UNLIMITED)
        streams[id] = StreamState(channel, decode)
        return channel
    }

    fun close(id: Long) {
        streams.remove(id)?.channel?.close()
    }

    fun closeAll() {
        streams.values.forEach { it.channel.close() }
        streams.clear()
    }

    /** Last sequence number received by each open stream, to ask the server for what was missed. */
    fun positions(): Map<Long, Long> = streams.mapValues { it.value.lastSeq }

    /** The server restarted: its streams are gone for good. */
    fun loseAll() = closeAll()

    fun onEvent(id: Long, payload: JsonElement) {
        val stream = streams[id] ?: return
        val event = payload.jsonObject
        val seq = event.long("seq")
        val snapshot = event.field("snapshot")
        synchronized(stream) {
            if (seq > stream.lastSeq) {
                stream.lastSeq = seq
                stream.deliver(snapshot)
            }
            endIfDone(id, stream)
        }
    }

    fun onEnd(id: Long, payload: JsonElement) {
        val stream = streams[id] ?: return
        synchronized(stream) {
            stream.endSeq = payload.jsonObject.long("seq")
            endIfDone(id, stream)
        }
    }

    private fun endIfDone(id: Long, stream: StreamState<*>) {
        if (stream.lastSeq >= stream.endSeq) {
            stream.channel.close()
            streams.remove(id)
        }
    }
}
