package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.int
import java.io.BufferedInputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val MIB = 1024 * 1024
private const val QUEUED_MS = 1_000L

private fun screen(seq: Int, text: String) =
    buildJsonObject {
        put("seq", seq)
        put("text", text)
    }

// Text that compresses little: larger than the socket buffers once compressed, so that its write blocks.
private fun noise(bytes: Int): String = Base64.getEncoder().encodeToString(Random(bytes).nextBytes(bytes * 3 / 4))

class ServerConnectionTest {
    private fun connected(block: (ServerConnection) -> Unit) = connectedTo { connection, _ -> block(connection) }

    private fun connectedTo(block: (ServerConnection, Socket) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { client ->
                server.accept().use { peer ->
                    val connection = ServerConnection(peer)
                    try {
                        block(connection, client)
                    } finally {
                        connection.close()
                    }
                }
            }
        }
    }

    @Test
    fun `small frames go before the large ones waiting to be written`() =
        connectedTo { connection, client ->
            // The client does not read yet: the writer is stuck in the first large frame, the others wait. Sent from
            // another thread, as a sender may wait for the writer.
            val frames =
                listOf(
                    Frame(FrameKind.Response, 1, JsonPrimitive(noise(24 * MIB))),
                    Frame(FrameKind.Response, 2, JsonPrimitive(noise(MIB))),
                    Frame(FrameKind.Response, 3, JsonPrimitive("small")),
                    Frame(FrameKind.Event, 4, screen(0, "screen")),
                )
            frames.forEach { it.body } // encoded beforehand: only their compression is left to the sender
            val sender = thread { frames.forEach(connection::send) }
            // Once the four are queued (or written, if the socket buffers took the first one whole).
            Thread.sleep(QUEUED_MS)
            val input = BufferedInputStream(client.getInputStream())
            Protocol.newCodec().use { codec ->
                val order = List(4) { Protocol.read(input, codec).id }
                assertTrue(order.indexOf(3) < order.indexOf(2), "the small answer waited for the large one: $order")
                assertTrue(order.indexOf(4) < order.indexOf(2), "the screen waited for the large answer: $order")
                assertTrue(order.indexOf(1) < order.indexOf(2), "the large answers were swapped: $order")
            }
            sender.join()
        }

    @Test
    fun `the screens of a stream keep their order whatever their size`() =
        connectedTo { connection, client ->
            val sizes = listOf(2 * MIB, 10, MIB, 10, 10)
            sizes.forEachIndexed { seq, size -> connection.send(Frame(FrameKind.Event, 9, screen(seq, noise(size)))) }
            connection.send(Frame(FrameKind.End, 9, buildJsonObject { put("seq", sizes.size) }))
            val input = BufferedInputStream(client.getInputStream())
            Protocol.newCodec().use { codec ->
                val received =
                    List(sizes.size + 1) {
                        val screen = Protocol.read(input, codec).payload.jsonObject
                        screen.int("seq")
                    }
                assertEquals(listOf(0, 1, 2, 3, 4, 5), received)
            }
        }

    @Test
    fun `an answer completed after the connection closed is dropped`() =
        connected { connection ->
            // A request finishing while its connection is being closed: the codec is already closed. The answer
            // must be dropped like any write to a lost connection (the client resends the request), not thrown.
            connection.close()
            connection.send(Frame(FrameKind.Response, 1, JsonPrimitive("abc".repeat(10_000))))
        }

    @Test
    fun `reading from a closed connection fails like the closed socket`() =
        connected { connection ->
            connection.close()
            assertFailsWith<IOException> { connection.read() }
        }
}
