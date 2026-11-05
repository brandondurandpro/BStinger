package org.example.kotlineditor.server

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.Protocol
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.lang.management.ManagementFactory
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

private const val MIB = 1024 * 1024
private const val CRLF = "\r\n"
private const val RLE_BLOCK = 128 * 1024
private const val BOMB_SIZE = 256 * MIB

// Far below the 256 MiB a bomb announces, far above what a refused message costs.
private const val ALLOCATION_BUDGET = 64L * MIB
private const val HANDSHAKE_TIMEOUT_MS = 500L
private const val CLOSE_WAIT_MS = 5_000
private const val DRIP_DELAY_MS = 100L

/**
 * A zstd frame of [size] bytes of spaces made of RLE blocks: four bytes per 128 KiB, so a few kilobytes announce
 * hundreds of megabytes (the frame declares its content size, which a decoder allocates at once).
 */
internal fun zstdBomb(size: Int): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte()))
    // Frame header descriptor: a 4-byte content size, single segment, no checksum, no dictionary.
    out.write(0xA0)
    for (shift in 0 until Int.SIZE_BITS step Byte.SIZE_BITS) out.write(size ushr shift and 0xFF)
    var left = size
    while (left > 0) {
        val block = minOf(RLE_BLOCK, left)
        left -= block
        val last = if (left == 0) 1 else 0
        // Block header, little endian: last block flag, block type 1 (RLE), then the number of repetitions.
        val header = last or (1 shl 1) or (block shl 3)
        for (shift in 0 until 3 * Byte.SIZE_BITS step Byte.SIZE_BITS) out.write(header ushr shift and 0xFF)
        out.write(' '.code)
    }
    return out.toByteArray()
}

private fun compressedHeaders(length: Int) = "Content-Length: $length$CRLF" + "Content-Encoding: zstd$CRLF$CRLF"

private fun allocatedBytes(): Long {
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    return threads.totalThreadAllocatedBytes
}

/** What the server accepts from a peer before and after it presented its token. */
class HandshakeLimitsTest {
    private val data = Files.createTempDirectory("handshake-data")
    private val server =
        EditorServer(LocalServices(data.toFile()), options = ServerOptions(handshakeTimeoutMs = HANDSHAKE_TIMEOUT_MS))
            .start()
    private val codec = Protocol.newCodec()

    @AfterTest
    fun cleanup() {
        codec.close()
        server.close()
        data.toFile().deleteRecursively()
    }

    private fun connect() = Socket("127.0.0.1", server.port).apply { soTimeout = CLOSE_WAIT_MS }

    private fun Socket.sendRaw(headers: String, body: ByteArray = ByteArray(0)) {
        getOutputStream().write(headers.encodeToByteArray() + body)
        getOutputStream().flush()
    }

    private fun Socket.hello(): BufferedInputStream {
        val params =
            buildJsonObject {
                put("version", Protocol.VERSION)
                put("token", server.token)
            }
        Protocol.write(getOutputStream(), Frame(FrameKind.Hello, Protocol.HELLO_ID, params), codec)
        val input = BufferedInputStream(getInputStream())
        assertEquals(FrameKind.Welcome, Protocol.read(input, codec).kind)
        return input
    }

    // The server closed the connection: end of stream, or a reset when it left bytes unread.
    private fun Socket.assertClosed(input: java.io.InputStream = getInputStream()) {
        try {
            while (input.read() >= 0) Unit
        } catch (expectedNot: SocketTimeoutException) {
            fail("the connection is still open after $CLOSE_WAIT_MS ms", expectedNot)
        } catch (expected: IOException) {
            // closed with unread bytes
        }
    }

    private fun assertCheap(block: () -> Unit) {
        val before = allocatedBytes()
        block()
        val allocated = allocatedBytes() - before
        assertTrue(allocated < ALLOCATION_BUDGET, "the server allocated ${allocated / MIB} MiB")
    }

    @Test
    fun `the test bomb is a valid zstd frame of the size it announces`() {
        assertEquals(MIB, codec.decompress(zstdBomb(MIB)).size)
    }

    @Test
    fun `a compressed hello is refused without being decompressed`() =
        assertCheap {
            connect().use { socket ->
                val bomb = zstdBomb(BOMB_SIZE)
                socket.sendRaw(compressedHeaders(bomb.size), bomb)
                socket.assertClosed()
            }
        }

    @Test
    fun `an oversized hello is refused before its body is read`() =
        assertCheap {
            connect().use { socket ->
                socket.sendRaw("Content-Length: ${64 * MIB}\r\n\r\n")
                socket.assertClosed()
            }
        }

    @Test
    fun `a connection still in its handshake when the server closes is closed unanswered`() {
        connect().use { waiting ->
            // Accepted in order: once a later connection is welcomed, the server is reading the first one's hello.
            connect().use { it.hello() }
            server.close()
            // A hello meant for the server that replaces this one on its port: the closed one must neither reject it
            // (the client would stop trying) nor welcome it.
            val params =
                buildJsonObject {
                    put("version", Protocol.VERSION)
                    put("token", "the next server's token")
                }
            val hello = Frame(FrameKind.Hello, Protocol.HELLO_ID, params)
            runCatching { Protocol.write(waiting.getOutputStream(), hello, codec) }
            val first = runCatching { waiting.getInputStream().read() }.getOrDefault(-1)
            assertEquals(-1, first, "the closed server answered the hello")
        }
    }

    @Test
    fun `a connection that never says hello is closed after the handshake timeout`() {
        connect().use { socket -> socket.assertClosed() }
    }

    @Test
    fun `a hello sent a byte at a time is closed at the handshake deadline`() {
        connect().use { socket ->
            val dripping =
                thread(isDaemon = true) {
                    runCatching {
                        "Content-Length: 2\r\n".repeat(CLOSE_WAIT_MS / DRIP_DELAY_MS.toInt()).forEach {
                            socket.getOutputStream().write(it.code)
                            Thread.sleep(DRIP_DELAY_MS)
                        }
                    }
                }
            socket.assertClosed()
            dripping.interrupt()
        }
    }

    @Test
    fun `a compressed request announcing more than a request can hold closes the connection unread`() {
        connect().use { socket ->
            val input = socket.hello()
            assertCheap {
                val bomb = zstdBomb(BOMB_SIZE)
                socket.sendRaw(compressedHeaders(bomb.size), bomb)
                socket.assertClosed(input)
            }
        }
    }

    @Test
    fun `compressed requests are accepted once the client presented its token`() {
        connect().use { socket ->
            val input = socket.hello()
            val params = buildJsonObject { put("padding", "x".repeat(MIB)) }
            Protocol.write(socket.getOutputStream(), Frame(FrameKind.Request, 1, params, Method.RecentLoad), codec)
            val answer = Protocol.read(input, codec)
            assertEquals(FrameKind.Response, answer.kind)
            assertEquals(1, answer.id)
        }
    }
}
