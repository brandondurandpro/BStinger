package org.example.kotlineditor.server

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.text
import org.example.kotlineditor.rpc.RejectedException
import org.example.kotlineditor.rpc.RemoteException
import org.example.kotlineditor.rpc.RemoteFileSystem
import org.example.kotlineditor.rpc.RemoteSearch
import org.example.kotlineditor.rpc.RemoteTerminalLauncher
import org.example.kotlineditor.rpc.RpcClient
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.measureTime

/** The client and the server talking over a real socket. */
class RemoteServicesTest {
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
    fun `file operations go through the server`() =
        runBlocking {
            project.resolve("a.txt").writeText("one\ntwo")
            val root = fs.resolveDirectory(project.toString())!!
            assertEquals(listOf("a.txt"), fs.list(root).map { it.name })
            val a = fs.list(root).single()
            assertEquals(listOf("one", "two"), (fs.read(a) as FileContent.Text).lines)
            fs.write(a, "changed")
            assertEquals("changed", Files.readString(project.resolve("a.txt")))
            val created = fs.createFile(root, "pkg/New.kt")
            assertEquals("New.kt", created.name)
            val renamed = fs.rename(created, "Renamed.kt")
            assertTrue(Files.exists(project.resolve("pkg/Renamed.kt")))
            fs.delete(renamed)
            assertTrue(!Files.exists(project.resolve("pkg/Renamed.kt")))
        }

    @Test
    fun `server errors come back as exceptions with their message`() =
        runBlocking {
            val root = fs.resolveDirectory(project.toString())!!
            fs.createFile(root, "dup.txt")
            val error = assertFailsWith<RemoteException> { fs.createFile(root, "dup.txt") }
            assertTrue("dup.txt" in error.message.orEmpty())
        }

    @Test
    fun `a wrong token is rejected`() =
        runBlocking {
            val intruder = RpcClient("127.0.0.1", server.port, "wrong")
            try {
                assertFailsWith<RejectedException> { RemoteFileSystem(intruder).resolveDirectory("/") }
            } finally {
                intruder.close()
            }
        }

    @Test
    fun `thousands of concurrent requests are multiplexed on one connection`() =
        runBlocking {
            repeat(20) { project.resolve("f$it.txt").writeText("content $it") }
            val root = fs.resolveDirectory(project.toString())!!
            val files = fs.list(root)
            val reads = (0 until 2000).map { i -> async { fs.read(files[i % files.size]) as FileContent.Text } }
            val results = reads.awaitAll()
            assertEquals(2000, results.size)
            assertTrue(results.all { it.lines.single().startsWith("content ") })
            assertEquals(1L, client.connections.get())
        }

    @Test
    fun `round trips are fast and large files transfer quickly`() =
        runBlocking {
            project.resolve("small.txt").writeText("x")
            project.resolve("big.kt").writeText((1..200_000).joinToString("\n") { "fun f$it(x: Int) = x * $it" })
            val root = fs.resolveDirectory(project.toString())!!
            val small = fs.fileAt(project.resolve("small.txt").toString())!!
            repeat(200) { fs.read(small) } // warm-up
            val sequential = measureTime { repeat(2000) { fs.read(small) } }
            val perCall = sequential.inWholeMicroseconds / 2000
            println("Sequential round trip: ${perCall}µs")
            assertTrue(perCall < 2_000, "round trip took ${perCall}µs")
            val big = fs.fileAt(project.resolve("big.kt").toString())!!
            val transfer = measureTime { assertEquals(200_000, (fs.read(big) as FileContent.Text).lines.size) }
            println("200k lines read through the server: $transfer")
            assertTrue(transfer.inWholeMilliseconds < 3_000, "transfer took $transfer")
            assertTrue(RemoteSearch(client).walkFiles(root).size == 2)
        }

    @Test
    fun `a remote terminal streams its screen`() =
        runBlocking {
            val terminal = RemoteTerminalLauncher(client).start(project.toString(), 80, 24, 8, 16)
            terminal.write("echo hello\n".toByteArray())
            val screen =
                withTimeout(10_000) {
                    terminal.output.first { snapshot -> snapshot.text().any { it.trim() == "hello" } }
                }
            assertTrue(screen.text().any { it.trim() == "hello" })
            terminal.destroy()
        }

    @Test
    fun `commands reach the terminal in order`() =
        runBlocking {
            val terminal = RemoteTerminalLauncher(client).start(project.toString(), 80, 160, 8, 16)
            repeat(50) { terminal.write("echo n$it\n".toByteArray()) }
            terminal.write("exit\n".toByteArray())
            val screen = withTimeout(20_000) { terminal.output.first { snapshot -> !snapshot.running } }
            val lines = screen.text().map { it.trim() }.filter { it.startsWith("n") }
            assertEquals((0 until 50).map { "n$it" }, lines)
            terminal.destroy()
        }
}
