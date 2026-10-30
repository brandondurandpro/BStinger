package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import org.example.kotlineditor.model.TerminalSnapshot
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-pushed terminal screens. Each event carries the sequence number of the complete snapshot it holds:
 * duplicates are dropped, and a snapshot that arrives after a gap simply replaces the state, because every
 * snapshot describes the whole screen.
 */
internal class StreamRegistry {
    private val streams = ConcurrentHashMap<Long, StreamState>()

    private class StreamState(val channel: Channel<TerminalSnapshot>) {
        var lastSeq = 0L
        var endSeq = Long.MAX_VALUE
    }

    fun open(id: Long): Channel<TerminalSnapshot> {
        val channel = Channel<TerminalSnapshot>(Channel.UNLIMITED)
        streams[id] = StreamState(channel)
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

    fun onEvent(id: Long, body: ByteArray) {
        val stream = streams[id] ?: return
        val reader = WireReader(body)
        val seq = reader.long()
        val snapshot = reader.snapshot()
        synchronized(stream) {
            if (seq > stream.lastSeq) {
                stream.lastSeq = seq
                stream.channel.trySend(snapshot)
            }
            endIfDone(id, stream)
        }
    }

    fun onEnd(id: Long, body: ByteArray) {
        val stream = streams[id] ?: return
        synchronized(stream) {
            stream.endSeq = WireReader(body).long()
            endIfDone(id, stream)
        }
    }

    private fun endIfDone(id: Long, stream: StreamState) {
        if (stream.lastSeq >= stream.endSeq) {
            stream.channel.close()
            streams.remove(id)
        }
    }
}
