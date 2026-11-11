package org.example.kotlineditor.server

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.OutcomeUnknownException
import org.example.kotlineditor.rpc.ClientOptions
import org.example.kotlineditor.rpc.RemoteFileSystem
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.HookedTelemetry
import org.example.kotlineditor.telemetry.InMemoryHook
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.telemetry.Side
import org.example.kotlineditor.telemetry.Span
import org.example.kotlineditor.telemetry.TelemetryHook
import org.example.kotlineditor.testkit.ChaosProxy
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val CUTS = 20
private const val SLOW_EXPORT_MS = 50L

/** Network failures injected between the client and the server: the IDE must not notice them. */
class ResilienceTest {
    private val data = Files.createTempDirectory("resilience-data")
    private val project = Files.createTempDirectory("resilience-project")
    private val token = "secret"
    private var server = EditorServer(LocalServices(data.toFile()), token).start()
    private val proxy = ChaosProxy(server.port)
    private val options = ClientOptions(heartbeatMs = 200, deadAfterMs = 800, maxBackoffMs = 200)
    private val client = RpcClient("127.0.0.1", proxy.port, token, options)
    private val fs = RemoteFileSystem(client)

    @AfterTest
    fun cleanup() {
        client.close()
        proxy.close()
        server.close()
        data.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    @Test
    fun `requests survive repeated connection cuts`() =
        runBlocking {
            project.resolve("a.txt").writeText("stable")
            val file = fs.fileAt(project.resolve("a.txt").toString())!!
            val chaos =
                launch {
                    repeat(15) {
                        delay(40)
                        proxy.cutAll()
                    }
                }
            repeat(300) { assertEquals(listOf("stable"), (fs.read(file) as FileContent.Text).lines) }
            chaos.join()
            assertTrue(client.connections.value > 1, "the client reconnected ${client.connections.value - 1} times")
        }

    @Test
    fun `every reconnection is measured, even one back before anyone looked`() =
        runBlocking {
            val spans = InMemoryHook()
            // A slow export, as on a loaded machine: the link comes back while the last measure is still being
            // written, so Reconnecting is over before a collector of the status could see it.
            val slow =
                object : TelemetryHook {
                    override fun onEnd(span: Span, durationNanos: Long, outcome: Outcome, attributes: Attributes) {
                        Thread.sleep(SLOW_EXPORT_MS)
                    }
                }
            val telemetry = HookedTelemetry(listOf(spans, slow), Side.Client)
            val measured = RpcClient("127.0.0.1", proxy.port, token, options, telemetry)
            val reconnections = { spans.spans.count { it.operation == Operation.Reconnect } }
            try {
                measured.awaitConnected()
                repeat(CUTS) { cut ->
                    proxy.cutAll()
                    withTimeout(5_000) { measured.connections.first { it == cut + 2L } }
                }
                withTimeout(5_000) { while (reconnections() < CUTS) delay(20) }
                assertEquals(CUTS, reconnections())
            } finally {
                measured.close()
            }
        }

    @Test
    fun `a mutation whose answer is lost is retried but applied only once`() =
        runBlocking {
            val root = fs.resolveDirectory(project.toString())!!
            client.awaitConnected()
            // The request reaches the server, the answer is lost with the connection.
            proxy.cutOnNextServerData = true
            val created = fs.createFile(root, "once.txt") // retried after reconnecting: no "already exists" error
            assertEquals("once.txt", created.name)
            assertTrue(client.connections.value >= 2)
            assertEquals(1, Files.list(project).use { it.count() }.toInt())
        }

    @Test
    fun `a mutation whose answer is lost while the server restarts is reported as unknown, not run again`() =
        runBlocking {
            val root = fs.resolveDirectory(project.toString())!!
            client.awaitConnected()
            // The request reaches the server and runs, its answer is lost; then the server restarts.
            proxy.dropFromServer = true
            val created = async { runCatching { fs.createFile(root, "once.txt") } }
            withTimeout(5_000) { while (!Files.exists(project.resolve("once.txt"))) delay(10) }
            restartServer()
            proxy.dropFromServer = false
            proxy.cutAll()

            val failure = created.await().exceptionOrNull()
            assertIs<OutcomeUnknownException>(failure, "a new execution would fail with \"already exists\"")
            assertTrue("fs/createFile" in failure.message.orEmpty())
            assertEquals(1, Files.list(project).use { it.count() }.toInt())
        }

    @Test
    fun `a read whose answer is lost while the server restarts is sent again`() =
        runBlocking {
            project.resolve("a.txt").writeText("read twice")
            val file = fs.fileAt(project.resolve("a.txt").toString())!!
            client.awaitConnected()
            proxy.dropFromServer = true
            val read = async { fs.read(file) }
            delay(200) // reaches the server, which answers into the void
            restartServer()
            proxy.dropFromServer = false
            proxy.cutAll()
            assertEquals(listOf("read twice"), (read.await() as FileContent.Text).lines)
        }

    // Stops the server and starts a new one on the same port and data: its sessions are gone.
    private fun restartServer() {
        val port = server.port
        server.close()
        server = EditorServer(LocalServices(data.toFile()), token).start(port = port)
    }

    @Test
    fun `a silent dead link is detected by the heartbeat and replaced`() =
        runBlocking {
            project.resolve("a.txt").writeText("x")
            client.awaitConnected()
            proxy.blackHole = true
            withTimeout(5_000) { while (client.status.value == ConnectionStatus.Connected) delay(20) }
            proxy.blackHole = false
            val file = fs.fileAt(project.resolve("a.txt").toString())!!
            assertEquals(listOf("x"), (fs.read(file) as FileContent.Text).lines)
            assertEquals(ConnectionStatus.Connected, client.status.value)
        }

    @Test
    fun `the client reconnects to a restarted server`() =
        runBlocking {
            project.resolve("a.txt").writeText("after restart")
            client.awaitConnected()
            val port = server.port
            server.close()
            delay(300)
            server = EditorServer(LocalServices(data.toFile()), token).start(port = port)
            val file = fs.fileAt(project.resolve("a.txt").toString())!!
            assertEquals(listOf("after restart"), (fs.read(file) as FileContent.Text).lines)
        }

    @Test
    fun `a client refused after a restart with a new token connects again with it`() =
        runBlocking {
            project.resolve("b.txt").writeText("new secret")
            client.awaitConnected()
            val port = server.port
            server.close()
            delay(300)
            server = EditorServer(LocalServices(data.toFile()), "another secret").start(port = port)
            // The old token is refused: the client stops, as retrying with it would not help.
            withTimeout(10_000) { client.status.first { it == ConnectionStatus.Disconnected } }

            client.reconnectWith("another secret")
            client.awaitConnected()
            val file = fs.fileAt(project.resolve("b.txt").toString())!!
            assertEquals(listOf("new secret"), (fs.read(file) as FileContent.Text).lines)
        }

    @Test
    fun `a new token given before the restart is used at once`() =
        runBlocking {
            project.resolve("c.txt").writeText("ahead")
            client.awaitConnected()
            val port = server.port
            server.close()
            // Given once the old server is gone, as the IDE does when it finds the daemon restarted: the old server
            // would refuse the new token, and a reconnection while it still runs (a heartbeat missed on a slow
            // machine) would then stop the client.
            client.reconnectWith("next secret")
            delay(300)
            server = EditorServer(LocalServices(data.toFile()), "next secret").start(port = port)
            val file = fs.fileAt(project.resolve("c.txt").toString())!!
            assertEquals(listOf("ahead"), (fs.read(file) as FileContent.Text).lines)
            assertEquals(ConnectionStatus.Connected, client.status.value)
        }
}
