package org.example.kotlineditor.server

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.rpc.ClientOptions
import org.example.kotlineditor.rpc.RemoteFileSystem
import org.example.kotlineditor.rpc.RemoteShellLauncher
import org.example.kotlineditor.rpc.RpcClient
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
            assertTrue(client.connections.get() > 1, "the client reconnected ${client.connections.get() - 1} times")
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
            assertTrue(client.connections.get() >= 2)
            assertEquals(1, Files.list(project).use { it.count() }.toInt())
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
    fun `shell output is complete, ordered and without duplicates across disconnections`() =
        runBlocking {
            val shell = RemoteShellLauncher(client).start(project.toString())
            shell.send("i=1; while [ \$i -le 400 ]; do echo line\$i; i=\$((i+1)); sleep 0.002; done; exit")
            val chaos =
                launch {
                    repeat(6) {
                        delay(120)
                        proxy.cutAll()
                    }
                }
            val lines = withTimeout(30_000) { shell.output.toList() }
            chaos.join()
            assertEquals((1..400).map { "line$it" }, lines)
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
}
