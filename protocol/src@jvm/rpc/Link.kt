package org.example.kotlineditor.rpc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

private const val BUFFER_SIZE = 64 * 1024

/**
 * One TCP connection carrying frames; writes are serialized, reads happen on a single thread. Frames are shown to
 * [tap] while it is active (devtools).
 */
internal class Link(private val socket: Socket, private val tap: NetworkTap? = null) : Closeable {
    // Counted above the buffers: the counts are the bytes of the frames, not of the socket reads.
    private val counted = CountingInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
    private val written = CountingOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))
    private val input = DataInputStream(counted)
    private val output = DataOutputStream(written)

    // One codec per connection: the native contexts are not thread-safe and keep the dictionary loaded.
    private val codec = Protocol.newCodec()

    @Volatile
    var lastSent = System.currentTimeMillis()
        private set

    fun read(): Frame {
        val before = counted.count
        val frame = Protocol.read(input, codec)
        if (tap?.active == true) tap.received(frame, (counted.count - before).toInt())
        return frame
    }

    /** Never throws: a failed write closes the socket, which makes the reader fail and the client reconnect. */
    fun send(frame: Frame) {
        try {
            synchronized(output) {
                val before = written.count
                val tapped = tap?.takeIf { it.active }
                tapped?.sending(frame)
                Protocol.write(output, frame, codec)
                tapped?.sent(frame, (written.count - before).toInt())
            }
            lastSent = System.currentTimeMillis()
        } catch (ignored: IOException) {
            close() // the reading side notices the closed socket and reconnects
        }
    }

    override fun close() {
        runCatching { codec.close() }
        runCatching { socket.close() }
    }
}
