package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.RpcClient
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerStateTest {
    private val data = Files.createTempDirectory("server-state-data")
    private val server = EditorServer(LocalServices(data.toFile()), token = "secret").start()
    private val state = File(data.toFile(), "state.properties")

    @AfterTest
    fun cleanup() {
        server.close()
        data.toFile().deleteRecursively()
    }

    @Test
    fun `the state file tells the port, the token and the process`() {
        ServerState.write(state, server, "127.0.0.1", "secret")

        val properties = assertNotNull(ServerState.read(state))
        assertEquals(Protocol.APP_VERSION, properties.getProperty("version"))
        assertEquals(Protocol.VERSION.toString(), properties.getProperty("protocol"))
        assertEquals("127.0.0.1", properties.getProperty("host"))
        assertEquals(server.port.toString(), properties.getProperty("port"))
        assertEquals(ProcessHandle.current().pid().toString(), properties.getProperty("pid"))
        assertEquals("secret", properties.getProperty("token"))
        assertNull(properties.getProperty("build"), "a server started by hand has no bundle id")
    }

    @Test
    fun `the state file tells which bundle the daemon runs`() {
        ServerState.write(state, server, "127.0.0.1", "secret", build = "3f9c0a7d21e84b6c")

        assertEquals("3f9c0a7d21e84b6c", assertNotNull(ServerState.read(state)).getProperty("build"))
    }

    @Test
    fun `the state file is only readable by its owner`() {
        ServerState.write(state, server, "127.0.0.1", "secret")

        if (ServerState.permissions(state) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), ServerState.permissions(state))
        }
    }

    @Test
    fun `the state file counts the sessions as clients come and their sessions expire`() {
        val shortGrace = EditorServer(LocalServices(data.toFile()), "secret", ServerOptions(sessionGraceMs = 200))
        shortGrace.start()
        val tracker = ServerState.track(state, shortGrace, "127.0.0.1", "secret", intervalMs = 20)
        try {
            assertEquals("0", ServerState.read(state)?.getProperty("sessions"))
            val client = RpcClient("127.0.0.1", shortGrace.port, "secret")
            runBlocking { client.awaitConnected() }
            awaitSessions("1")
            // A disconnected session still counts while its shells wait for the client to come back.
            client.close()
            awaitSessions("0")
        } finally {
            tracker.close()
            shortGrace.close()
        }
    }

    private fun awaitSessions(expected: String) {
        val deadline = System.currentTimeMillis() + 10_000
        while (ServerState.read(state)?.getProperty("sessions") != expected) {
            check(System.currentTimeMillis() < deadline) { "sessions never became $expected" }
            Thread.sleep(10)
        }
    }

    @Test
    fun `a shutdown only removes the state file it wrote`() {
        ServerState.write(state, server, "127.0.0.1", "secret")
        ServerState.removeIfOwned(state)
        assertTrue(!state.exists())

        state.writeText(
            """
            version=${Protocol.APP_VERSION}
            port=${server.port}
            pid=1
            token=other
            """.trimIndent(),
        )
        ServerState.removeIfOwned(state)
        assertTrue(state.exists(), "another server's state file must survive")
        assertNull(ServerState.read(File(data.toFile(), "missing")))
    }

    @Test
    fun `the daemon options are read from the command line`() {
        val options =
            ServerMain.LaunchOptions.from(
                arrayOf(
                    "--host", "127.0.0.1",
                    "--port", "0",
                    "--token-file", "/tmp/token",
                    "--state-file", "/tmp/state",
                    "--data-dir", "/tmp/data",
                    "--session-grace", "60",
                    "--build", "3f9c0a7d21e84b6c",
                    "--version",
                ),
            )

        assertEquals("127.0.0.1", options.host)
        assertEquals(0, options.port)
        assertEquals(File("/tmp/token"), options.tokenFile)
        assertEquals(File("/tmp/state"), options.stateFile)
        assertEquals(File("/tmp/data"), options.dataDirectory)
        assertEquals(60, options.sessionGraceSeconds)
        assertEquals("3f9c0a7d21e84b6c", options.build)
        assertTrue(options.version)
        assertNull(ServerMain.LaunchOptions.from(arrayOf("--build", "")).build)
    }
}
