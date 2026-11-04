package org.example.kotlineditor.rpc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket

private const val BUFFER_SIZE = 64 * 1024

/**
 * One TCP connection carrying JSON-RPC messages; writes are serialized, reads happen on a single thread. Messages are
 * shown to [tap] while it is active (devtools).
 */
internal class Link(private val socket: Socket, private val tap: NetworkTap? = null) : Closeable {
    // Counted above the buffers: the counts are the bytes of the messages, not of the socket reads.
    private val counted = CountingInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
    private val written = CountingOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))

    // One codec per connection: the native contexts are not thread-safe and keep the dictionary loaded.
    private val codec = Protocol.newCodec()

    @Volatile
    private var closed = false

    @Volatile
    var lastSent = System.currentTimeMillis()
        private set

    fun read(): Frame {
        val before = counted.count
        val frame = closedAsIOException { Protocol.read(counted, codec) }
        if (tap?.active == true) tap.received(frame, (counted.count - before).toInt())
        return frame
    }

    /** Never throws: a failed write closes the socket, which makes the reader fail and the client reconnect. */
    fun send(frame: Frame) {
        try {
            synchronized(written) {
                val before = written.count
                val tapped = tap?.takeIf { it.active }
                tapped?.sending(frame)
                closedAsIOException { Protocol.write(written, frame, codec) }
                tapped?.sent(frame, (written.count - before).toInt())
            }
            lastSent = System.currentTimeMillis()
        } catch (ignored: IOException) {
            close() // the reading side notices the closed socket and reconnects
        }
    }

    override fun close() {
        closed = true
        runCatching { socket.close() }
        runCatching { codec.close() }
    }

    // A frame already buffered can still be decoded after close(): the closed codec then throws an
    // IllegalStateException, which must look like the closed socket it stands for.
    private inline fun <T> closedAsIOException(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            if (closed) throw IOException("Link closed", e) else throw e
        }
}
