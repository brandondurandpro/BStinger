package org.example.kotlineditor.rpc

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.OutcomeUnknownException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val LARGE_TEXT = 32 * 1024 * 1024
private const val PROBE_TIMEOUT_S = 3L
private const val WAIT_MS = 10_000L

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

    // Takes the server down and waits for [client] to have noticed: what it queues then waits for [comeBack].
    private suspend fun FakeServer.goDown(client: RpcClient) {
        down = true
        cutAll()
        withTimeout(WAIT_MS) { client.status.first { it == ConnectionStatus.Reconnecting } }
    }

    private suspend fun FakeServer.comeBack(client: RpcClient, before: Long) {
        down = false
        withTimeout(WAIT_MS) { client.connections.first { it > before } }
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
            // Both are queued before the writer sees either: whatever the speed of the machine, the save is not
            // prepared yet when the writer finds the read behind it.
            val before = client.connections.value
            server.goDown(client)
            val save =
                launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) {
                    client.call(Method.Write) { put("text", text) }
                }
            val read = launch(Dispatchers.IO, CoroutineStart.UNDISPATCHED) { client.call(Method.Read) }
            server.comeBack(client, before)
            val first = server.next()
            val second = server.next()
            assertEquals(Method.Read, first.method)
            assertEquals(Method.Write, second.method)
            val aheadMs = (second.atNanos - first.atNanos) / 1_000_000
            println("PERF small request read ${aheadMs}ms before the 32 MB save queued ahead of it")
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
            // All queued before the writer sees any: the paste cannot be prepared before the others are known.
            val before = client.connections.value
            server.goDown(client)
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
            server.comeBack(client, before)
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

    @Test
    fun `a sent mutation fails as unknown when the server restarted, a read is sent again`() =
        runBlocking {
            // The second connection reaches a new server: its session is not resumed.
            val server = FakeServer(resumed = { false }).closedAfter()
            val client = clientOf(server)
            client.awaitConnected()
            val created =
                async(Dispatchers.IO) { runCatching { client.call(Method.CreateFile) { put("name", "once.txt") } } }
            val read = async(Dispatchers.IO) { client.call(Method.Read) }
            val sent = setOf(server.next().method, server.next().method)
            assertEquals(setOf(Method.CreateFile, Method.Read), sent)
            server.cutAll()

            val failure = assertFailsWith<OutcomeUnknownException> { created.await().getOrThrow() }
            assertTrue("fs/createFile" in failure.message.orEmpty(), failure.message)
            val replayed = server.next()
            assertEquals(2, replayed.connection)
            assertEquals(Method.Read, replayed.method)
            server.answer(replayed.id, "content")
            assertEquals("\"content\"", read.await().toString())
            // Pending requests are sent again first: a mutation sent again would arrive before this one.
            val probe = async(Dispatchers.IO) { runCatching { client.call(Method.LspHover) } }
            assertEquals(Method.LspHover, server.next().method, "the mutation is not run again")
            probe.cancelAndJoin()
        }

    @Test
    fun `a mutation queued while the server was down is sent to the restarted server`() =
        runBlocking<Unit> {
            val server = FakeServer(resumed = { false }).closedAfter()
            val client = clientOf(server)
            client.awaitConnected()
            val before = client.connections.value
            server.goDown(client)
            val created =
                async(Dispatchers.IO, CoroutineStart.UNDISPATCHED) {
                    client.call(Method.CreateFile) { put("name", "later.txt") }
                }
            server.comeBack(client, before)
            val request = server.next()
            assertEquals(Method.CreateFile, request.method)
            server.answer(request.id, "created")
            assertEquals("\"created\"", created.await().toString())
        }
}
