package org.example.kotlineditor.rpc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LARGE_TEXT = 32 * 1024 * 1024
private const val PROBE_TIMEOUT_S = 3L
private const val WAIT_MS = 10_000L
private const val SMALL_REQUEST_BUDGET_MS = 150L

// A paste or an edit larger than the threshold under which frames keep their queued order.
private const val LARGE_PASTE = 8 * LARGE_FRAME_CHARS

private val options = ClientOptions(heartbeatMs = 60_000, deadAfterMs = 60_000, maxBackoffMs = 50)

/** What the client writes, in which order and on which thread, against a [FakeServer]. */
class RpcClientTest {
    private val closing = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        closing.asReversed().forEach { runCatching { it.close() } }
    }

    private fun <T : AutoCloseable> T.closedAfter(): T = also { closing += it }

    private fun clientOf(server: FakeServer) = RpcClient("127.0.0.1", server.port, "token", options).closedAfter()

    private fun randomLetters(size: Int): String {
        val random = Random(size)
        val letters = CharArray(size) { 'a' + random.nextInt(26) }
        return String(letters)
    }

    private fun FakeServer.next(): Received = checkNotNull(requests.poll(WAIT_MS, TimeUnit.MILLISECONDS))

    @Test
    fun `a call does not block its thread while the server does not read`() =
        runBlocking<Unit> {
            val server = FakeServer(reads = false).closedAfter()
            val client = clientOf(server)
            client.awaitConnected()
            val ui = Executors.newSingleThreadExecutor().closedAfter()
            val text = randomLetters(LARGE_TEXT)
            val save =
                launch(ui.asCoroutineDispatcher()) { runCatching { client.call(Method.Write) { put("text", text) } } }
            // The socket's buffers fill up and the write blocks: on the writer's thread, not on the caller's.
            val probe = ui.submit<Unit> { }
            try {
                probe.get(PROBE_TIMEOUT_S, TimeUnit.SECONDS)
            } finally {
                // Unblocks a write stuck on the caller's thread, so that a failure ends.
                client.close()
                save.join()
            }
        }

    @Test
    fun `a small request is written before a large one queued just before it`() =
        runBlocking {
            val server = FakeServer().closedAfter()
            val client = clientOf(server)
            client.awaitConnected()
            val text = randomLetters(LARGE_TEXT)
            val save =
                launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) {
                    client.call(Method.Write) { put("text", text) }
                }
            val queuedAt = System.nanoTime()
            val read = launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { client.call(Method.Read) }
            val first = server.next()
            val second = server.next()
            assertEquals(Method.Read, first.method)
            assertEquals(Method.Write, second.method)
            val waitedMs = (first.atNanos - queuedAt) / 1_000_000
            println("PERF small request written ${waitedMs}ms after being queued behind a 32 MB save")
            // Encoding, compressing and writing the save first takes hundreds of milliseconds.
            assertTrue(waitedMs < SMALL_REQUEST_BUDGET_MS, "written ${waitedMs}ms after being queued")
            save.cancelAndJoin()
            read.cancelAndJoin()
        }

    @Test
    fun `frames the server runs in order are not reordered by their size`() =
        runBlocking {
            val server = FakeServer().closedAfter()
            val client = clientOf(server)
            client.awaitConnected()
            val paste = randomLetters(LARGE_PASTE)
            val calls =
                listOf(
                    launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) {
                        client.call(Method.TerminalPaste) { put("text", paste) }
                    },
                    launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) {
                        client.call(Method.TerminalKey) { put("key", "Enter") }
                    },
                    launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) {
                        client.call(Method.DocChange) { put("path", "a.kt") }
                    },
                    // Not ordered by the server: it may go first.
                    launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { client.call(Method.LspHover) },
                )
            val received = List(calls.size) { server.next().method }
            assertEquals(Method.LspHover, received.firstOrNull())
            assertEquals(listOf(Method.TerminalPaste, Method.TerminalKey, Method.DocChange), received.drop(1))
            calls.forEach { it.cancelAndJoin() }
        }

    @Test
    fun `pending requests are sent again in id order after a reconnection, each once per connection`() =
        runBlocking {
            val server = FakeServer().closedAfter()
            val client = clientOf(server)
            client.awaitConnected()
            val count = 200
            val calls =
                List(count) { index ->
                    // Some are queued while the connection is being replaced.
                    if (index == count / 2) server.cutAll()
                    async(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { client.call(Method.LspHover) }
                }
            withTimeout(WAIT_MS) { client.connections.first { it >= 2 } }
            val ids = calls.indices.map { it + 1L }.toSet()
            val seen = mutableListOf<Received>()
            withTimeout(WAIT_MS) {
                while (seen.filter { it.connection == server.connections }.map { it.id }.toSet() != ids) {
                    seen += server.requests.poll(WAIT_MS, TimeUnit.MILLISECONDS) ?: break
                }
            }
            val last = seen.filter { it.connection == server.connections }.map { it.id }
            assertEquals(ids, last.toSet(), "every pending request reaches the new connection")
            assertEquals(last.sorted(), last, "they are sent again in id order")
            assertEquals(last.distinct(), last, "no request is sent twice on one connection")
            for (connection in 1..server.connections) {
                val sent = seen.filter { it.connection == connection }.map { it.id }
                assertEquals(sent.distinct(), sent, "no request is sent twice on connection $connection")
            }
            calls.forEach { it.cancel() }
        }
}
