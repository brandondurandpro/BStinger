package org.example.kotlineditor.rpc

import java.io.BufferedOutputStream
import java.io.DataOutputStream
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
                    // Two compressed frames sent at once: reading the first buffers the second.
                    val body = WireWriter().string("abc".repeat(100_000)).toByteArray()
                    val out = DataOutputStream(BufferedOutputStream(peer.getOutputStream()))
                    val codec = Protocol.newCodec()
                    Protocol.write(out, Frame(FrameKind.Response, 1, body), codec)
                    Protocol.write(out, Frame(FrameKind.Response, 2, body), codec)
                    out.flush()
                    val sent = out.size()
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
