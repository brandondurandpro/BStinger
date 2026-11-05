package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.string
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val ANSWER_TIMEOUT_MS = 5_000
private const val SILENCE_MS = 500
private const val SLOW_MS = 1_500L

/** A [ServerSession] fed frames as the reading thread does, its answers read at the other end of a loopback socket. */
private class SessionHarness(handlers: Map<Method, Handler>) : AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val listener = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val client = Socket(listener.inetAddress, listener.localPort)
    private val connection = ServerConnection(listener.accept())
    private val session = ServerSession("test", scope, handlers, ServerOptions())
    private val input = BufferedInputStream(client.getInputStream())
    private val codec = Protocol.newCodec()

    init {
        session.attach(connection)
    }

    fun request(id: Long, method: Method, params: JsonObject = JsonObject(emptyMap())) =
        session.onFrame(connection, Frame(FrameKind.Request, id, params, method))

    fun ping() = session.onFrame(connection, Frame(FrameKind.Ping, 0))

    fun cancel(id: Long) = session.onFrame(connection, Frame(FrameKind.Cancel, id))

    /** The next frame, or null when none arrives within [timeoutMs]. */
    fun next(timeoutMs: Int = ANSWER_TIMEOUT_MS): Frame? {
        client.soTimeout = timeoutMs
        return try {
            Protocol.read(input, codec)
        } catch (expected: SocketTimeoutException) {
            null
        }
    }

    fun answer(): Frame = assertNotNull(next(), "no answer within $ANSWER_TIMEOUT_MS ms")

    override fun close() {
        session.dispose()
        scope.cancel()
        codec.close()
        client.close()
        listener.close()
    }
}

private fun terminal(id: Long) = buildJsonObject { put("terminal", id) }

private fun document(path: String) =
    buildJsonObject {
        put("path", path)
        put("version", 1)
    }

private fun Frame.errorCode(): Int = payload.jsonObject.int("code")

class ServerSessionTest {
    @Test
    fun `a handler failing unexpectedly is answered with an internal error`() {
        val failing: Handler = { throw NullPointerException("boom") }
        SessionHarness(mapOf(Method.GitStatus to failing)).use { harness ->
            harness.request(1, Method.GitStatus)
            val answer = harness.answer()
            assertEquals(FrameKind.Error, answer.kind)
            assertEquals(1, answer.id)
            assertEquals(Protocol.INTERNAL_ERROR, answer.errorCode())
        }
    }

    @Test
    fun `a request whose handler failed unexpectedly runs again when it is retried`() {
        val runs = AtomicInteger()
        val failing: Handler = {
            runs.incrementAndGet()
            throw IndexOutOfBoundsException()
        }
        SessionHarness(mapOf(Method.GitStatus to failing)).use { harness ->
            harness.request(1, Method.GitStatus)
            assertEquals(FrameKind.Error, harness.answer().kind)
            harness.request(1, Method.GitStatus)
            assertEquals(FrameKind.Error, harness.answer().kind)
            assertEquals(2, runs.get())
        }
    }

    @Test
    fun `an ordered handler failing unexpectedly is answered and does not reach the reading thread`() {
        val failing: Handler = { throw NullPointerException() }
        SessionHarness(mapOf(Method.TerminalWrite to failing)).use { harness ->
            harness.request(2, Method.TerminalWrite, terminal(1))
            val answer = harness.answer()
            assertEquals(2, answer.id)
            assertEquals(Protocol.INTERNAL_ERROR, answer.errorCode())
        }
    }

    @Test
    fun `a timeout inside a handler is answered as a failure`() {
        val timingOut: Handler = {
            withTimeout(10) { delay(10_000) }
            JsonNull
        }
        SessionHarness(mapOf(Method.GitStatus to timingOut)).use { harness ->
            harness.request(3, Method.GitStatus)
            val answer = harness.answer()
            assertEquals(FrameKind.Error, answer.kind)
            assertEquals(Protocol.SERVER_ERROR, answer.errorCode())
        }
    }

    @Test
    fun `a cancelled request is not answered and can be sent again`() {
        val runs = AtomicInteger()
        val slow: Handler = {
            if (runs.incrementAndGet() == 1) delay(10_000)
            JsonPrimitive("done")
        }
        SessionHarness(mapOf(Method.GitStatus to slow)).use { harness ->
            harness.request(4, Method.GitStatus)
            harness.cancel(4)
            assertNull(harness.next(SILENCE_MS))
            harness.request(4, Method.GitStatus)
            assertEquals(FrameKind.Response, harness.answer().kind)
            assertEquals(2, runs.get())
        }
    }

    @Test
    fun `a replayed code lens command runs once`() {
        val runs = AtomicInteger()
        val counting: Handler = {
            runs.incrementAndGet()
            JsonNull
        }
        SessionHarness(mapOf(Method.LspRunCodeLens to counting)).use { harness ->
            val params =
                buildJsonObject {
                    put("token", "t")
                    put("index", 0)
                }
            harness.request(7, Method.LspRunCodeLens, params)
            assertEquals(FrameKind.Response, harness.answer().kind)
            // The answer was lost: the client sends the request again after reconnecting.
            harness.request(7, Method.LspRunCodeLens, params)
            assertEquals(FrameKind.Response, harness.answer().kind)
            assertEquals(1, runs.get())
        }
    }

    @Test
    fun `a slow ordered request holds back neither a ping nor a request of another key`() {
        val slowStart: Handler = {
            // Blocking, like the spawning of a shell.
            Thread.sleep(SLOW_MS)
            JsonNull
        }
        val quick: Handler = { JsonPrimitive("status") }
        SessionHarness(mapOf(Method.TerminalStart to slowStart, Method.GitStatus to quick)).use { harness ->
            val started = System.nanoTime()
            harness.request(1, Method.TerminalStart)
            harness.ping()
            assertEquals(FrameKind.Pong, harness.answer().kind)
            harness.request(2, Method.GitStatus)
            assertEquals(2, harness.answer().id)
            val waitedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue(waitedMs < SLOW_MS, "the ping and the other request waited $waitedMs ms")
            assertEquals(1, harness.answer().id)
        }
    }

    @Test
    fun `the commands of a terminal apply in arrival order while another terminal goes on`() {
        val applied = ConcurrentLinkedQueue<String>()
        val write: Handler = {
            val text = params.string("text")
            if (text == "slow") Thread.sleep(SLOW_MS / 3)
            applied += "${params.long("terminal")}:$text"
            JsonNull
        }
        SessionHarness(mapOf(Method.TerminalWrite to write, Method.TerminalKey to write)).use { harness ->
            fun send(id: Long, method: Method, terminal: Long, text: String) =
                harness.request(
                    id,
                    method,
                    buildJsonObject {
                        put("terminal", terminal)
                        put("text", text)
                    },
                )
            send(1, Method.TerminalWrite, 5, "slow")
            send(2, Method.TerminalKey, 5, "a")
            send(3, Method.TerminalWrite, 5, "b")
            send(4, Method.TerminalWrite, 6, "c")
            val order = List(4) { harness.answer().id }
            assertEquals(4, order.firstOrNull(), "the other terminal waited: $order")
            assertEquals(listOf("6:c", "5:slow", "5:a", "5:b"), applied.toList())
        }
    }

    @Test
    fun `the edits of a document apply in order and a request about it waits for them`() {
        val applied = ConcurrentLinkedQueue<String>()
        val recording = { name: String, delayMs: Long ->
            val handler: Handler = {
                if (delayMs > 0) Thread.sleep(delayMs)
                applied += "$name ${params.string("path")}"
                JsonNull
            }
            handler
        }
        val handlers =
            mapOf(
                Method.DocOpen to recording("open", SLOW_MS / 3),
                Method.DocChange to recording("change", 0),
                Method.DocClose to recording("close", 0),
                Method.LspHover to recording("hover", 0),
            )
        SessionHarness(handlers).use { harness ->
            harness.request(1, Method.DocOpen, document("a.kt"))
            harness.request(2, Method.DocChange, document("a.kt"))
            harness.request(3, Method.LspHover, document("a.kt"))
            harness.request(4, Method.DocClose, document("a.kt"))
            harness.request(5, Method.LspHover, document("b.kt"))
            val order = List(5) { harness.answer().id }
            assertEquals(5, order.firstOrNull(), "a request about another document waited: $order")
            val sequence = applied.toList()
            assertEquals(listOf("hover b.kt", "open a.kt", "change a.kt"), sequence.take(3))
            // The hover waits for the edits sent before it, not for the requests sent after it.
            assertEquals(setOf("hover a.kt", "close a.kt"), sequence.drop(3).toSet())
        }
    }

    @Test
    fun `lsp use calls apply in arrival order`() {
        val applied = ConcurrentLinkedQueue<String>()
        val use: Handler = {
            val project = params.string("project")
            if (project == "first") Thread.sleep(SLOW_MS / 3)
            applied += project
            JsonNull
        }
        SessionHarness(mapOf(Method.LspUse to use)).use { harness ->
            harness.request(1, Method.LspUse, buildJsonObject { put("project", "first") })
            harness.request(2, Method.LspUse, buildJsonObject { put("project", "second") })
            assertEquals(listOf(1L, 2L), List(2) { harness.answer().id })
            assertEquals(listOf("first", "second"), applied.toList())
        }
    }

    @Test
    fun `an ordered request sent again while it waits in its queue runs once, even when cancelled`() {
        val runs = ConcurrentLinkedQueue<Long>()
        val release = CountDownLatch(1)
        val write: Handler = {
            if (id == 1L) release.await(ANSWER_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
            runs += id
            JsonNull
        }
        SessionHarness(mapOf(Method.TerminalWrite to write)).use { harness ->
            harness.request(1, Method.TerminalWrite, terminal(5))
            harness.request(2, Method.TerminalWrite, terminal(5))
            harness.request(2, Method.TerminalWrite, terminal(5))
            // The requests after it in its queue expect its effect: it is not dropped.
            harness.cancel(2)
            release.countDown()
            assertEquals(listOf(1L, 2L), List(2) { harness.answer().id })
            assertNull(harness.next(SILENCE_MS))
            assertEquals(listOf(1L, 2L), runs.toList())
        }
    }
}
