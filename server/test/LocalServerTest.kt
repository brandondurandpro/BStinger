package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The IDE's local server in a child JVM: started, restarted when it dies, stopped with the IDE. */
class LocalServerTest {
    private val data = Files.createTempDirectory("local-server-data")
    private val warnings = CopyOnWriteArrayList<String>()
    private val opened = mutableListOf<AutoCloseable>()

    // No ripgrep lookup (it could download one): the built-in search is enough here.
    private val serverArguments =
        listOf("--data-dir", data.toString(), "--ripgrep", data.resolve("no-ripgrep").toString())

    @AfterTest
    fun cleanup() {
        opened.asReversed().forEach { runCatching { it.close() } }
        data.toFile().deleteRecursively()
    }

    private fun start(
        java: String? = LocalServer.javaLauncher(),
        classpath: String = System.getProperty("java.class.path"),
        binary: String? = null,
    ): LocalServer {
        val settings =
            LocalServer.Settings(
                java = java,
                binary = binary,
                classpath = classpath,
                serverArguments = serverArguments,
                services = { LocalServices(data.toFile()) },
                warn = { warnings += it },
            )
        return LocalServer.start(settings).also { opened += it }
    }

    private fun clientOf(server: LocalServer): RpcClient =
        RpcClient("127.0.0.1", server.port, server.token).also { opened += it }

    private fun answers(client: RpcClient): Boolean =
        runBlocking {
            client.awaitConnected(CONNECT_TIMEOUT_MS)
            client.appServices().recentProjects.load()
            true
        }

    @Test
    fun `the server runs in a child process that the client reaches`() {
        assertNotNull(LocalServer.javaLauncher(), "the tests run on a JDK")
        val server = start()

        assertTrue(server.awaitListening(CONNECT_TIMEOUT_MS), "the child should listen: $warnings")
        val pid = assertNotNull(server.childPid)
        assertNotEquals(ProcessHandle.current().pid(), pid)
        assertFalse(server.inProcess)
        assertTrue(answers(clientOf(server)))
    }

    @Test
    fun `a child that dies is started again on the same port and the client reconnects`() {
        val server = start()
        val client = clientOf(server)
        assertTrue(answers(client))
        val first = assertNotNull(server.childPid)
        val connections = client.connections.value

        ProcessHandle.of(first).ifPresent { it.destroyForcibly() }

        awaitCondition("a new child") { server.childPid.let { it != null && it != first } }
        assertTrue(server.awaitListening(CONNECT_TIMEOUT_MS))
        awaitCondition("the client back") {
            client.connections.value > connections && client.status.value == ConnectionStatus.Connected
        }
        assertTrue(answers(client), "requests go through the new child")
        assertFalse(server.inProcess)
    }

    @Test
    fun `closing the server stops the child and leaves no process behind`() {
        val server = start()
        assertTrue(server.awaitListening(CONNECT_TIMEOUT_MS))
        val pid = assertNotNull(server.childPid)

        server.close()

        assertFalse(ProcessHandle.of(pid).map { it.isAlive }.orElse(false), "the child must be stopped")
        val left =
            ProcessHandle
                .current()
                .descendants()
                .filter { it.isAlive && runsLocalServer(it) }
                .count()
        assertEquals(0, left, "no server process may be left")
    }

    @Test
    fun `the server stops when the process that started it ends`() {
        val parent = startJava("-cp", System.getProperty("java.class.path"), Sleeper::class.java.name)
        val state = data.resolve("state.properties").toFile()
        val token = data.resolve("token")
        TokenFile.write(token, "secret")
        val server =
            startJava(
                "-cp", System.getProperty("java.class.path"), ServerMain::class.java.name,
                "--port", "0", "--token-file", token.toString(), "--state-file", state.toString(),
                "--parent-pid", parent.pid().toString(), *serverArguments.toTypedArray(),
            )
        awaitCondition("the server state") { ServerState.read(state)?.getProperty("pid") == "${server.pid()}" }

        parent.destroyForcibly()

        assertTrue(server.waitFor(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS), "the server must exit with its parent")
        assertFalse(state.exists(), "an exiting server removes its state file")
    }

    @Test
    fun `without a java launcher the server runs in the IDE's JVM`() {
        val server = start(java = null)

        assertTrue(server.inProcess)
        assertEquals(null, server.childPid)
        assertTrue(answers(clientOf(server)))
        assertTrue(warnings.any { "IDE's JVM" in it }, "the fallback is reported: $warnings")
    }

    @Test
    fun `a java launcher that cannot run falls back to the IDE's JVM on the same port`() {
        val missing = File(data.toFile(), "no-such-java").path
        val server = start(java = missing)

        assertTrue(server.inProcess)
        assertTrue(answers(clientOf(server)))
        assertTrue(warnings.any { missing in it }, "the failure is reported: $warnings")
    }

    @Test
    fun `a child that dies at every start falls back to the IDE's JVM, and the client gets there`() {
        // No server class on this class path: each child exits at once.
        val server = start(classpath = data.resolve("empty").toString())
        val client = clientOf(server)

        awaitCondition("the fallback") { server.inProcess }
        assertTrue(answers(client), "the client created at start reaches the fallback server")
        assertTrue(warnings.any { "while starting" in it }, "the fallback is reported: $warnings")
    }

    @Test
    fun `a native binary that cannot run falls back to the IDE's JVM, and the failure is reported`() {
        val missing = File(data.toFile(), "no-such-server").path
        val server = start(binary = missing)

        assertTrue(server.inProcess)
        assertTrue(answers(clientOf(server)))
        assertTrue(warnings.any { missing in it }, "the failure is reported: $warnings")
    }

    @Test
    fun `the native binary is the configured file, when it can run and no server plugin is installed`() {
        val plugins = File(data.toFile(), "server-plugins")
        val binary = File(data.toFile(), "server").apply { writeText("") }
        assertEquals(null, LocalServer.nativeBinary(null, plugins))
        assertEquals(null, LocalServer.nativeBinary("  ", plugins))
        assertEquals(null, LocalServer.nativeBinary(File(data.toFile(), "missing").path, plugins))
        // Windows has no executable bit: every file can be executed there.
        if (!binary.canExecute()) assertEquals(null, LocalServer.nativeBinary(binary.path, plugins))
        binary.setExecutable(true)
        assertEquals(binary.path, LocalServer.nativeBinary(binary.path, plugins))
        // A native binary cannot load the jar of a plugin: the server then runs on a JVM.
        File(plugins, "some-plugin").mkdirs()
        assertEquals(null, LocalServer.nativeBinary(binary.path, plugins))
    }

    private fun startJava(vararg arguments: String): Process {
        val java = assertNotNull(LocalServer.javaLauncher())
        val process =
            ProcessBuilder(listOf(java) + arguments)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
        opened += AutoCloseable { process.destroyForcibly() }
        return process
    }

    private fun runsLocalServer(process: ProcessHandle): Boolean {
        val line = process.info().commandLine()
        return line.isPresent && "server.args" in line.get()
    }

    private fun awaitCondition(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for $what; warnings: $warnings" }
            Thread.sleep(POLL_MS)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val POLL_MS = 20L
    }
}

/** A process that only waits, standing for the IDE that started a server. */
object Sleeper {
    /** Waits until killed. */
    @JvmStatic
    fun main(args: Array<String>) {
        Thread.sleep(Long.MAX_VALUE)
    }
}
