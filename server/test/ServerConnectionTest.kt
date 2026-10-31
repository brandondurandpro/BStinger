package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertFailsWith

class ServerConnectionTest {
    private fun connected(block: (ServerConnection) -> Unit) {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { _ ->
                server.accept().use { peer -> block(ServerConnection(peer)) }
            }
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
