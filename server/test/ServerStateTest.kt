package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Protocol
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
    }

    @Test
    fun `the state file is only readable by its owner`() {
        ServerState.write(state, server, "127.0.0.1", "secret")

        if (ServerState.permissions(state) != null) {
            assertEquals(PosixFilePermissions.fromString("rw-------"), ServerState.permissions(state))
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
                    "--version",
                ),
            )

        assertEquals("127.0.0.1", options.host)
        assertEquals(0, options.port)
        assertEquals(File("/tmp/token"), options.tokenFile)
        assertEquals(File("/tmp/state"), options.stateFile)
        assertEquals(File("/tmp/data"), options.dataDirectory)
        assertEquals(60, options.sessionGraceSeconds)
        assertTrue(options.version)
    }
}
