package org.example.kotlineditor.server

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The server compiled to a native binary (`./native-server build`), started by the IDE in place of a child JVM
 * and reached through the protocol. What a native binary may lack is what GraalVM could not see while compiling:
 * the classes found through a `ServiceLoader` (the plugins of the server), the libraries loaded from a resource
 * (zstd, the pseudo-terminal), the C functions called through the Foreign Function & Memory API. Each test goes
 * through one of them.
 *
 * Skipped unless `KOTLIN_EDITOR_SERVER_BINARY` names the binary: `./native-server test` builds it and runs them.
 */
class NativeServerTest {
    private val binary: String? = System.getenv("KOTLIN_EDITOR_SERVER_BINARY")?.takeIf { File(it).canExecute() }
    private val data = Files.createTempDirectory("native-server-data")
    private val project = Files.createTempDirectory("native-server-project")
    private val warnings = CopyOnWriteArrayList<String>()
    private val opened = mutableListOf<AutoCloseable>()

    // No ripgrep lookup (it could download one): the built-in search is enough here.
    private val serverArguments =
        listOf("--data-dir", data.toString(), "--ripgrep", data.resolve("no-ripgrep").toString())

    @AfterTest
    fun cleanup() {
        opened.asReversed().forEach { runCatching { it.close() } }
        data.toFile().deleteRecursively()
        project.toFile().deleteRecursively()
    }

    // Runs [test] against the native server; does nothing where no binary was built.
    private fun withNativeServer(test: suspend (LocalServer, RpcClient, AppServices) -> Unit) {
        val native = binary ?: return
        val settings =
            LocalServer.Settings(
                binary = native,
                serverArguments = serverArguments,
                services = { LocalServices(data.toFile()) },
                warn = { warnings += it },
            )
        val server = LocalServer.start(settings).also { opened += it }
        assertTrue(server.awaitListening(TIMEOUT_MS), "the native server should listen: $warnings")
        val client = RpcClient("127.0.0.1", server.port, server.token).also { opened += it }
        runBlocking {
            client.awaitConnected(TIMEOUT_MS)
            test(server, client, client.appServices())
        }
        assertFalse(server.inProcess, "the server ran in the IDE's JVM: $warnings")
    }

    @Test
    fun `the native server is the child process, not a JVM`() =
        withNativeServer { server, _, services ->
            val pid = assertNotNull(server.childPid)
            assertNotEquals(ProcessHandle.current().pid(), pid)
            val command = ProcessHandle.of(pid).flatMap { it.info().command() }
            assertEquals(File(checkNotNull(binary)).canonicalPath, File(command.orElse("")).canonicalPath)
            assertEquals(emptyList(), services.recentProjects.load())
        }

    @Test
    fun `files are written, listed and read back, large ones compressed on the way`() =
        withNativeServer { _, _, services ->
            val files = services.fileSystem
            val root = assertNotNull(files.resolveDirectory(project.toString()))
            val created = files.createFile(root, "notes.txt")
            // Larger than a frame is sent uncompressed: the answer goes through zstd, a library loaded from a jar.
            val text = (0 until LARGE_LINES).joinToString("\n") { "ligne $it : é, ü, 日本語, the quick brown fox" }
            assertNotNull(files.write(created, text))
            assertEquals(listOf("notes.txt"), files.list(root).map { it.name })
            val read = files.read(assertNotNull(files.fileAt(created.path)))
            assertEquals(text, (read as FileContent.Text).lines.joinToString("\n"))
            assertEquals(text, project.resolve("notes.txt").toFile().readText())
        }

    @Test
    fun `the built-in search finds text in the files of a project`() =
        withNativeServer { _, _, services ->
            project.resolve("a.kt").toFile().writeText("val first = 1\nval needle = 2\n")
            project.resolve("b.kt").toFile().writeText("// nothing here\n")
            val root = assertNotNull(services.fileSystem.resolveDirectory(project.toString()))
            val matches = services.search.findInFiles(root, "needle", ignoreCase = false, limit = 10)
            assertEquals(listOf("a.kt" to 1), matches.map { it.entry.name to it.line })
            assertEquals(2, services.search.walkFiles(root).size)
        }

    @Test
    fun `a shell runs in a pseudo-terminal and its commands reach the disk`() =
        withNativeServer { _, _, services ->
            val terminal = services.terminal.start(project.toString(), COLUMNS, ROWS, CELL_WIDTH, CELL_HEIGHT)
            terminal.write("echo done > marker.txt\r".toByteArray())
            terminal.write("exit\r".toByteArray())
            withTimeout(TIMEOUT_MS) { terminal.output.first { snapshot -> !snapshot.running } }
            val marker = project.resolve("marker.txt").toFile()
            assertEquals("done", marker.readText().trim())
            terminal.destroy()
        }

    @Test
    fun `the plugins compiled into the binary answer, here Git on a directory that is no repository`() =
        withNativeServer { _, _, services ->
            assertEquals(null, services.git.branch(project.toString()))
            assertEquals(emptyMap(), services.git.status(project.toString()))
        }

    @Test
    fun `a native server that dies is started again on the same port`() =
        withNativeServer { server, client, services ->
            val first = assertNotNull(server.childPid)
            val connections = client.connections.value
            ProcessHandle.of(first).ifPresent { it.destroyForcibly() }
            awaitCondition("a new child") { server.childPid.let { it != null && it != first } }
            assertTrue(server.awaitListening(TIMEOUT_MS))
            awaitCondition("the client back") {
                client.connections.value > connections && client.status.value == ConnectionStatus.Connected
            }
            assertEquals(emptyList(), services.recentProjects.load())
        }

    /**
     * GraalVM sizes the heap of a binary from the machine: a young generation of 10% of 80% of its memory, 2.5 GB
     * on a 32 GB one, filled with garbage before anything is collected. The server answered a few large files and
     * kept hundreds of megabytes for good. The binary is built with a small young generation and gives back the
     * heap it no longer uses.
     */
    @Test
    fun `the memory of a burst of large answers is given back`() =
        withNativeServer { server, _, services ->
            val pid = assertNotNull(server.childPid)
            // Linux only: elsewhere nothing tells the memory of another process as cheaply.
            if (residentMegabytes(pid) != null) burstThenRest(pid, services)
        }

    private suspend fun burstThenRest(pid: Long, services: AppServices) {
        val atRest = checkNotNull(residentMegabytes(pid))
        val line = "a line of a large file, read again and again: 0123456789 abcdefghijklmnopqrstuvwxyz"
        project.resolve("large.txt").toFile().writeText((0 until BURST_LINES).joinToString("\n") { "$it $line" })
        val file = assertNotNull(services.fileSystem.fileAt(project.resolve("large.txt").toString()))
        var peak = atRest
        repeat(BURST_READS) {
            services.fileSystem.read(file)
            peak = maxOf(peak, checkNotNull(residentMegabytes(pid)))
        }
        awaitCondition("the memory to go back") { checkNotNull(residentMegabytes(pid)) <= RESIDENT_BUDGET_MB }
        val after = checkNotNull(residentMegabytes(pid))
        println("PERF native server resident memory: $atRest MB at rest, $peak MB peak, $after MB after")
        assertTrue(peak <= PEAK_BUDGET_MB, "the native server reached $peak MB")
    }

    // `VmRSS` of the process, or null where there is no `/proc`.
    private fun residentMegabytes(pid: Long): Long? {
        val status = File("/proc/$pid/status").takeIf { it.canRead() } ?: return null
        val line = status.readLines().firstOrNull { it.startsWith(RESIDENT) }
        val kilobytes = line?.removePrefix(RESIDENT)?.trim()?.substringBefore(' ')
        return kilobytes?.toLongOrNull()?.div(KILOBYTES_PER_MB)
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; warnings: $warnings" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 45_000L
        const val POLL_MS = 20L
        const val LARGE_LINES = 20_000
        const val BURST_LINES = 60_000
        const val BURST_READS = 60

        // Measured: 30 MB at rest, 110 MB at the peak, 50 MB half a minute later; 89 MB kept for good before.
        const val KILOBYTES_PER_MB = 1_024
        const val RESIDENT = "VmRSS:"
        const val RESIDENT_BUDGET_MB = 80L
        const val PEAK_BUDGET_MB = 400L
        const val COLUMNS = 80
        const val ROWS = 24
        const val CELL_WIDTH = 8
        const val CELL_HEIGHT = 16
    }
}
