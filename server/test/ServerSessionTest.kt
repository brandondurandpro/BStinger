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
}
