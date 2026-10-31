package org.example.kotlineditor.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.model.NetworkInspector
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.RemoteFileSystem
import org.example.kotlineditor.rpc.RpcClient
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val CRLF = "\r\n"

/** The devtools capture of a client talking to a real server. */
class NetworkInspectorTest {
    private val data = Files.createTempDirectory("server-data")
    private val project = Files.createTempDirectory("server-project")
    private val server = EditorServer(LocalServices(data.toFile())).start()
    private val client = RpcClient("127.0.0.1", server.port, server.token)
    private val fs = RemoteFileSystem(client)

    @AfterTest
    fun cleanup() {
        client.close()
        server.close()
        data.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    @Test
    fun `nothing is captured until recording starts`() =
        runBlocking {
            client.awaitConnected()
            fs.resolveDirectory(project.toString())
            assertTrue(client.network.snapshot().isEmpty())
            assertEquals(0, client.network.totals.value.frames)
        }

    @Test
    fun `requests and their answers are captured with method, sizes and latency`() =
        runBlocking {
            client.awaitConnected()
            client.network.recording = true
            project.resolve("a.txt").writeText("hello network")
            val root = fs.resolveDirectory(project.toString())!!
            fs.read(fs.list(root).single())

            val records = client.network.snapshot()
            val request = records.first { it.kind == "Request" && it.method == "Read" }
            val response = records.first { it.kind == "Response" && it.id == request.id }
            assertEquals(Direction.Sent, request.direction)
            assertEquals(Direction.Received, response.direction)
            assertEquals("Read", response.method)
            assertNotNull(response.latencyMs)
            assertTrue(response.body.decodeToString().contains("hello network"))
            // The wire adds the headers to the message, compressed when zstd saves enough: whether it does depends on
            // the length of the temporary path, so the expected size comes from the same codec.
            val compressed = Protocol.newCodec().use { it.compress(request.body, code = false) }
            val expected =
                if (compressed == null) {
                    request.size + "Content-Length: ${request.size}$CRLF$CRLF".length
                } else {
                    compressed.size + "Content-Length: ${compressed.size}${CRLF}Content-Encoding: zstd$CRLF$CRLF".length
                }
            assertEquals(expected, request.wireSize)
            val totals = client.network.totals.value
            assertEquals(records.size.toLong(), totals.frames)
            assertEquals(records.filter { it.direction == Direction.Sent }.sumOf { it.size.toLong() }, totals.sentBytes)
        }

    @Test
    fun `the token of a reconnection handshake is not kept`() =
        runBlocking {
            client.awaitConnected()
            client.network.recording = true
            val before = client.connections.get()
            server.dropConnections()
            while (client.connections.get() == before) delay(10)
            client.awaitConnected()
            val hello = client.network.snapshot().first { it.kind == "Hello" }
            assertTrue(hello.redacted)
            assertTrue(hello.body.isEmpty())
            assertTrue(hello.size > server.token.length)
        }

    @Test
    fun `the capture keeps the last frames within its limits`() {
        val inspector = NetworkInspector().apply { recording = true }
        val big = ByteArray(NetworkInspector.MAX_BODY * 2)
        inspector.record(Direction.Received, "Response", 1, "Read", big, big.size)
        val kept = inspector.snapshot().single()
        assertEquals(big.size, kept.size)
        assertEquals(NetworkInspector.MAX_BODY, kept.body.size)
        assertTrue(kept.truncated)

        val ping = ByteArray(0)
        repeat(NetworkInspector.MAX_RECORDS + 10) { inspector.record(Direction.Sent, "Ping", 0, null, ping, 14) }
        val records = inspector.snapshot()
        assertEquals(NetworkInspector.MAX_RECORDS, records.size)
        assertTrue(records.none { it.kind == "Response" })
        assertEquals(NetworkInspector.MAX_RECORDS + 11L, inspector.totals.value.frames)

        inspector.clear()
        assertTrue(inspector.snapshot().isEmpty())
        assertEquals(0, inspector.totals.value.frames)
    }
}
