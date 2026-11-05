package org.example.kotlineditor.rpc

import org.example.kotlineditor.compression.BodyCodec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket

private const val BUFFER_SIZE = 64 * 1024

/**
 * One TCP connection carrying JSON-RPC messages; writes are serialized, reads happen on a single thread. Messages are
 * shown to [tap] while it is active (devtools). Frames are [prepare]d (encoded and compressed) before being written,
 * so that the preparation of a large one does not hold the socket.
 */
internal class Link(private val socket: Socket, private val tap: NetworkTap? = null) : Closeable {
    // Counted above the buffer: the counts are the bytes of the messages, not of the socket reads.
    private val counted = CountingInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
    private val written = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)

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

    /**
     * Writes [frame] at once, on the calling thread; never throws: a failed write closes the socket, which makes the
     * reader fail and the client reconnect. Only for the handshake: afterwards frames go through the [Outbox].
     */
    fun send(frame: Frame) {
        try {
            write(prepare(frame))
        } catch (ignored: IOException) {
            close() // the reading side notices the closed socket and reconnects
        }
    }

    /**
     * The bytes of [frame] on the wire, encoded and compressed with [with] (the link's codec by default; another one
     * lets a large frame be compressed while the link's codec keeps serving small ones). Does not write anything.
     */
    fun prepare(frame: Frame, with: BodyCodec = codec): Prepared {
        val wire = ByteArrayOutputStream()
        closedAsIOException { Protocol.write(wire, frame, with) }
        return Prepared(frame, wire)
    }

    /**
     * Writes a [prepare]d frame, blocking until it is in the socket; one writer at a time. Throws [IOException] when
     * the link is broken.
     */
    fun write(prepared: Prepared) {
        val frame = prepared.frame
        val tapped = tap?.takeIf { it.active }
        // Recorded before the write: the answer can be read, and the call return, before the write does.
        tapped?.sending(frame)
        tapped?.sent(frame, prepared.size)
        synchronized(written) {
            prepared.bytes.writeTo(written)
            written.flush()
        }
        lastSent = System.currentTimeMillis()
    }

    /** A frame ready to be written: its whole message, headers included. */
    class Prepared(val frame: Frame, internal val bytes: ByteArrayOutputStream) {
        /** Bytes on the wire. */
        val size: Int get() = bytes.size()
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
