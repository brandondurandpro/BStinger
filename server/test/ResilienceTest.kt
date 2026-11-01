package org.example.kotlineditor.server

import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.SearchInterruptedException
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.SearchUpdate
import org.example.kotlineditor.model.text
import org.example.kotlineditor.rpc.ClientOptions
import org.example.kotlineditor.rpc.RemoteFileSystem
import org.example.kotlineditor.rpc.RemoteSearch
import org.example.kotlineditor.rpc.RemoteTerminalLauncher
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.testkit.ChaosProxy
import org.example.kotlineditor.testkit.ControlledSearch
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
    private var searches: ControlledSearch? = null
    private var server =
        EditorServer(LocalServices(data.toFile(), wrapSearch = { ControlledSearch(it).also { searches = it } }), token)
            .start()

    private val proxy = ChaosProxy(server.port)
    private val options = ClientOptions(heartbeatMs = 200, deadAfterMs = 800, maxBackoffMs = 200)
    private val client = RpcClient("127.0.0.1", proxy.port, token, options)
    private val fs = RemoteFileSystem(client)
    private val search = RemoteSearch(client)

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
    fun `terminal screens stay complete and ordered across disconnections`() =
        runBlocking {
            val terminal = RemoteTerminalLauncher(client).start(project.toString(), 80, 120, 8, 16)
            val script =
                if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                    "for /L %i in (1,1,400) do @echo line%i\rexit\r"
                } else {
                    "stty -echo; i=1; while [ \$i -le 400 ]; do echo line\$i; i=\$((i+1)); sleep 0.002; done; exit\r"
                }
            terminal.write(script.toByteArray())
            val chaos =
                launch {
                    repeat(6) {
                        delay(120)
                        proxy.cutAll()
                    }
                }
            val screen = withTimeout(30_000) { terminal.output.first { snapshot -> !snapshot.running } }
            chaos.join()
            val lines = screen.text().mapNotNull { Regex("""line\d+$""").find(it.trim())?.value }
            assertEquals((301..400).map { "line$it" }, lines.takeLast(100))
            terminal.destroy()
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

    private fun needles(files: Int): FileEntry {
        repeat(files) { project.resolve("f${it.toString().padStart(3, '0')}.txt").writeText("x needle $it\n") }
        return FileEntry(project.toString(), project.fileName.toString(), true)
    }

    private fun findNeedles(root: FileEntry) = search.findInFiles(root, SearchQuery("needle"), limit = 10_000)

    @Test
    fun `search results arrive exactly once across disconnections`() =
        runBlocking {
            val root = needles(300)
            checkNotNull(searches).pauseMs = 5
            client.awaitConnected()
            val chaos =
                launch {
                    repeat(8) {
                        delay(150)
                        proxy.cutAll()
                    }
                }
            val updates = withTimeout(60_000) { findNeedles(root).toList() }
            chaos.join()
            val matches = updates.filterIsInstance<SearchUpdate.Found>().flatMap { it.matches }
            assertEquals(300, matches.size)
            assertEquals(300, matches.map { it.entry.path }.distinct().size)
            assertEquals(true, (updates.last() as SearchUpdate.Finished).complete)
            assertEquals(1, checkNotNull(searches).started.get(), "the search ran once")
            assertTrue(client.connections.get() > 2, "the client reconnected ${client.connections.get() - 1} times")
        }

    @Test
    fun `a search whose start answer is lost is retried but runs once`() =
        runBlocking {
            val root = needles(20)
            client.awaitConnected()
            proxy.cutOnNextServerData = true
            val matches = withTimeout(30_000) { findNeedles(root).toList() }.filterIsInstance<SearchUpdate.Found>()
            assertEquals(20, matches.sumOf { it.matches.size })
            assertTrue(client.connections.get() >= 2)
            assertEquals(1, checkNotNull(searches).started.get())
        }

    @Test
    fun `a search stops on the server when the client stops listening`() =
        runBlocking {
            val root = needles(20)
            val controlled = checkNotNull(searches)
            controlled.hold()
            val first = withTimeout(10_000) { findNeedles(root).first() }
            assertTrue(first is SearchUpdate.Found)
            withTimeout(10_000) { while (controlled.stopped.get() == 0) delay(10) }
            controlled.release()
        }

    @Test
    fun `a search lost by a restarting server fails instead of looking complete`() =
        runBlocking {
            val root = needles(20)
            val controlled = checkNotNull(searches)
            controlled.hold()
            val updates = Channel<SearchUpdate>(Channel.UNLIMITED)
            val collecting =
                async {
                    runCatching { findNeedles(root).collect { updates.send(it) } }.exceptionOrNull()
                }
            withTimeout(10_000) { updates.receive() }
            val port = server.port
            server.close()
            server = EditorServer(LocalServices(data.toFile()), token).start(port = port)
            val failure = withTimeout(10_000) { collecting.await() }
            assertTrue(failure is SearchInterruptedException, "failed with $failure")
            controlled.release()
        }
}
