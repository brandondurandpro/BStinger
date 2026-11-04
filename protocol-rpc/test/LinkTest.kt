package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonPrimitive
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LinkTest {
    @Test
    fun `a frame still buffered when the link closes fails like the closed socket`() {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            Socket(server.inetAddress, server.localPort).use { socket ->
                server.accept().use { peer ->
                    // Two compressed messages sent at once: reading the first buffers the second.
                    val result = JsonPrimitive("abc".repeat(100_000))
                    val counted = CountingOutputStream(BufferedOutputStream(peer.getOutputStream()))
                    val codec = Protocol.newCodec()
                    Protocol.write(counted, Frame(FrameKind.Response, 1, result), codec)
                    Protocol.write(counted, Frame(FrameKind.Response, 2, result), codec)
                    val sent = counted.count
                    while (socket.getInputStream().available() < sent) Thread.sleep(5)

                    val link = Link(socket)
                    assertEquals(1, link.read().id)
                    link.close()
                    assertFailsWith<IOException> { link.read() }
                }
            }
        }
    }
}
