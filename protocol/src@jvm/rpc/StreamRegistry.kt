package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap

/**
 * Server-pushed streams (shell output). Events carry sequence numbers: they are delivered in order, exactly once,
 * whatever the network did (events arriving early are buffered, replayed ones are dropped).
 */
internal class StreamRegistry {
    private val streams = ConcurrentHashMap<Long, StreamState>()

    private class StreamState(val channel: Channel<List<String>>) {
        var lastSeq = 0L
        var endSeq = Long.MAX_VALUE
        val early = TreeMap<Long, List<String>>()
    }

    fun open(id: Long): Channel<List<String>> {
        val channel = Channel<List<String>>(Channel.UNLIMITED)
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
    fun loseAll() {
        streams.values.forEach { it.channel.trySend(listOf("[Connection to the server lost: process ended]")) }
        closeAll()
    }

    fun onEvent(id: Long, body: ByteArray) {
        val stream = streams[id] ?: return
        val reader = WireReader(body)
        val seq = reader.long()
        val reset = reader.bool()
        val lines = reader.strings()
        synchronized(stream) {
            // A reset means the server no longer has what came before: accept the gap instead of waiting forever.
            if (reset && seq > stream.lastSeq) {
                stream.channel.trySend(listOf("[... output lost while disconnected ...]"))
                stream.lastSeq = seq - 1
            }
            if (seq > stream.lastSeq) stream.early[seq] = lines
            deliverInOrder(id, stream)
        }
    }

    fun onEnd(id: Long, body: ByteArray) {
        val stream = streams[id] ?: return
        synchronized(stream) {
            stream.endSeq = WireReader(body).long()
            deliverInOrder(id, stream)
        }
    }

    private fun deliverInOrder(id: Long, stream: StreamState) {
        var next = stream.early.firstEntry()
        while (next != null && next.key <= stream.lastSeq + 1) {
            stream.early.pollFirstEntry()
            if (next.key == stream.lastSeq + 1) {
                stream.lastSeq = next.key
                stream.channel.trySend(next.value)
            }
            next = stream.early.firstEntry()
        }
        if (stream.lastSeq >= stream.endSeq) {
            stream.channel.close()
            streams.remove(id)
        }
    }
}
