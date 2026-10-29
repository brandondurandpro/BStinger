package org.example.kotlineditor.rpc

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

private const val BUFFER_SIZE = 64 * 1024

/** One TCP connection carrying frames; writes are serialized, reads happen on a single thread. */
internal class Link(private val socket: Socket) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_SIZE))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_SIZE))

    @Volatile
    var lastSent = System.currentTimeMillis()
        private set

    fun read(): Frame = Protocol.read(input)

    /** Never throws: a failed write closes the socket, which makes the reader fail and the client reconnect. */
    fun send(frame: Frame) {
        try {
            synchronized(output) { Protocol.write(output, frame) }
            lastSent = System.currentTimeMillis()
        } catch (ignored: IOException) {
            close() // the reading side notices the closed socket and reconnects
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }
}
