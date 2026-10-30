package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

private const val BUFFER_SIZE = 64 * 1024

/** One client connection; writes are serialized, a failed write closes it (the session then waits for the client). */
internal class ServerConnection(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))

    // One codec per connection: the native contexts are not thread-safe and keep the dictionary loaded.
    private val codec = Protocol.newCodec()

    fun read(): Frame = Protocol.read(input, codec)

    fun send(frame: Frame) {
        try {
            synchronized(output) { Protocol.write(output, frame, codec) }
        } catch (ignored: IOException) {
            close() // the reading thread ends; the client reconnects and retries or resumes
        }
    }

    override fun close() {
        runCatching { codec.close() }
        runCatching { socket.close() }
    }
}
