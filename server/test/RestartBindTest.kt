package org.example.kotlineditor.server

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.rpc.RpcClient
import java.net.BindException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.fail

/** Restarting a server on the port it just released, as after a crash. */
class RestartBindTest {
    private val data = Files.createTempDirectory("restart-bind-data")

    @AfterTest
    fun cleanup() {
        data.toFile().deleteRecursively()
    }

    @Test
    fun `restarting on the same port with a connected client`() =
        runBlocking {
            val token = "secret"
            var server = EditorServer(LocalServices(data.toFile()), token).start()
            val port = server.port
            val client = RpcClient("127.0.0.1", port, token)
            client.awaitConnected()
            repeat(100) { index ->
                server.close()
                try {
                    server = EditorServer(LocalServices(data.toFile()), token).start(port = port)
                } catch (e: BindException) {
                    fail("iteration $index: ${e.message}")
                }
            }
            client.close()
            server.close()
        }

    @Test
    fun `start waits for a port another listener is about to release`() {
        // Windows SO_REUSEADDR binds a port another socket holds, so the wait this test checks only happens on Unix.
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) return
        val blocker =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("127.0.0.1", 0))
            }
        try {
            val port = blocker.localPort
            val started = CompletableFuture<EditorServer>()
            val failed = CompletableFuture<Throwable>()
            val worker =
                thread(name = "restart-bind-test") {
                    try {
                        started.complete(EditorServer(LocalServices(data.toFile()), "secret").start(port = port))
                    } catch (e: Throwable) {
                        failed.complete(e)
                    }
                }
            Thread.sleep(200)
            assertFalse(started.isDone || failed.isDone, "the server must wait instead of failing the restart")
            blocker.close()
            started.get(5, TimeUnit.SECONDS).close()
            worker.join()
        } finally {
            runCatching { blocker.close() }
        }
    }
}
