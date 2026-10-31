package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.Socket

private const val BUFFER_SIZE = 64 * 1024

/** One client connection; writes are serialized, a failed write closes it (the session then waits for the client). */
internal class ServerConnection(private val socket: Socket) : Closeable {
    private val input = BufferedInputStream(socket.getInputStream(), BUFFER_SIZE)
    private val output = BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE)

    // One codec per connection: the native contexts are not thread-safe and keep the dictionary loaded.
    private val codec = Protocol.newCodec()

    @Volatile
    private var closed = false

    fun read(): Frame = closedAsIOException { Protocol.read(input, codec) }

    /** Never throws: an answer for a lost connection is dropped, the client resends the request or resumes. */
    fun send(frame: Frame) {
        if (closed) return
        try {
            synchronized(output) { closedAsIOException { Protocol.write(output, frame, codec) } }
        } catch (ignored: IOException) {
            close() // the reading thread ends; the client reconnects and retries or resumes
        }
    }

    override fun close() {
        closed = true
        runCatching { codec.close() }
        runCatching { socket.close() }
    }

    // A request can complete while another thread closes the connection: the closed codec then throws an
    // IllegalStateException, which must look like the closed socket it stands for.
    private inline fun <T> closedAsIOException(block: () -> T): T =
        try {
            block()
        } catch (e: IllegalStateException) {
            if (closed) throw IOException("Connection closed", e) else throw e
        }
}
