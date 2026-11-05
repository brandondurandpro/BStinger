package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/** A request as the fake server read it: on which connection (from 1), with which id and method. */
class Received(val connection: Int, val id: Long, val method: Method?, val atNanos: Long = System.nanoTime())

/**
 * A server speaking the protocol just enough to test the client: it welcomes every connection, then [reads] the
 * frames it receives or reads nothing at all, and answers only what the test asks it to.
 */
class FakeServer(
    /** Whether it reads the frames after the handshake; when false the client's writes end up blocked. */
    private val reads: Boolean = true,
    /** Whether the session of the connection number n (from 1) is resumed: a restarted server answers false. */
    private val resumed: (Int) -> Boolean = { it > 1 },
) : Closeable {
    private val server = ServerSocket(0, 0, InetAddress.getLoopbackAddress())
    private val sockets = CopyOnWriteArrayList<Socket>()

    /** Requests received, in arrival order. */
    val requests = LinkedBlockingQueue<Received>()

    val port: Int get() = server.localPort

    @Volatile var connections = 0
        private set

    /** While true, connections are closed as soon as they are accepted, as when the server cannot be reached. */
    @Volatile var down = false

    init {
        thread(isDaemon = true, name = "fake-server-accept") {
            var socket = runCatching { server.accept() }.getOrNull()
            while (socket != null) {
                if (down) {
                    socket.close()
                    socket = runCatching { server.accept() }.getOrNull()
                    continue
                }
                connections++
                val connection = connections
                sockets += socket
                val accepted = socket
                thread(isDaemon = true, name = "fake-server-$connection") { serve(accepted, connection) }
                socket = runCatching { server.accept() }.getOrNull()
            }
        }
    }

    /** Answers the request [id] on the last connection with [result]. */
    fun answer(id: Long, result: String) {
        val socket = sockets.lastOrNull() ?: return
        Protocol.newCodec().use { codec ->
            synchronized(socket) {
                val out = BufferedOutputStream(socket.getOutputStream())
                Protocol.write(out, Frame(FrameKind.Response, id, JsonPrimitive(result)), codec)
            }
        }
    }

    /** Breaks every connection, as a network failure would. */
    fun cutAll() {
        sockets.forEach { runCatching { it.close() } }
        sockets.clear()
    }

    override fun close() {
        server.close()
        cutAll()
    }

    private fun serve(socket: Socket, connection: Int) {
        try {
            Protocol.newCodec().use { codec ->
                val input = BufferedInputStream(socket.getInputStream())
                Protocol.read(input, codec) // the hello
                val welcome =
                    buildJsonObject {
                        put("session", "s")
                        put("resumed", resumed(connection))
                    }
                synchronized(socket) {
                    val out = BufferedOutputStream(socket.getOutputStream())
                    Protocol.write(out, Frame(FrameKind.Welcome, Protocol.HELLO_ID, welcome), codec)
                }
                while (reads) {
                    val frame = Protocol.read(input, codec)
                    if (frame.kind == FrameKind.Request) requests += Received(connection, frame.id, frame.method)
                }
            }
        } catch (ignored: IOException) {
            // the connection was cut
        }
    }
}
