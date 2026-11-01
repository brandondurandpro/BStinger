package org.example.kotlineditor.lsp.jsonrpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import java.io.InputStream
import java.io.OutputStream
import java.nio.channels.Channels
import java.nio.channels.Pipe
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val TIMEOUT_MS = 10_000L

/** JSON-RPC over two in-memory pipes, the test playing the language server. */
class LspConnectionTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val toServer = Pipe.open()
    private val toClient = Pipe.open()
    private val serverInput: InputStream = Channels.newInputStream(toServer.source()).buffered()
    private val serverOutput: OutputStream = Channels.newOutputStream(toClient.sink())
    private val closed = CompletableFuture<Throwable?>()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        runCatching { serverOutput.close() }
    }

    private fun connect(
        onRequest: LspRequestHandler = { method, _ -> throw unknown(method) },
        onNotification: LspNotificationHandler = { _, _ -> },
    ) = LspConnection(
        Channels.newInputStream(toClient.source()),
        Channels.newOutputStream(toServer.sink()),
        scope,
        onRequest,
        onNotification,
        onClose = { closed.complete(it) },
    ).start()

    private fun unknown(method: String) = LspResponseException(LspConnection.METHOD_NOT_FOUND, method)

    private fun received(): JsonObject {
        val body = checkNotNull(LspFraming.read(serverInput)).decodeToString()
        return Json.parseToJsonElement(body).jsonObject
    }

    private fun sendToClient(text: String) = LspFraming.write(serverOutput, text.toByteArray())

    @Test
    fun `concurrent requests are matched to answers that come in any order`() =
        runBlocking<Unit> {
            val connection = connect()
            val first = scope.async { connection.request("first") }
            val second = scope.async { connection.request("second") }
            val requests = listOf(received(), received()).associateBy { it["method"]!!.jsonPrimitive.content }
            for (method in listOf("second", "first")) {
                val id = requests.getValue(method)["id"]!!.jsonPrimitive.long
                sendToClient("""{"jsonrpc":"2.0","id":$id,"result":"answer to $method"}""")
            }
            assertEquals(JsonPrimitive("answer to first"), withTimeout(TIMEOUT_MS) { first.await() })
            assertEquals(JsonPrimitive("answer to second"), withTimeout(TIMEOUT_MS) { second.await() })
        }

    @Test
    fun `an error answer throws with its code`() =
        runBlocking<Unit> {
            val connection = connect()
            val call = scope.async { connection.request("textDocument/hover") }
            val id = received()["id"]!!.jsonPrimitive.long
            sendToClient("""{"jsonrpc":"2.0","id":$id,"error":{"code":-32801,"message":"content modified"}}""")
            val failure = assertFailsWith<LspResponseException> { withTimeout(TIMEOUT_MS) { call.await() } }
            assertEquals(-32_801, failure.code)
            assertEquals("content modified", failure.message)
        }

    @Test
    fun `cancelling a request tells the server`() =
        runBlocking<Unit> {
            val connection = connect()
            val call = scope.async { connection.request("textDocument/completion") }
            val id = received()["id"]!!.jsonPrimitive.long
            call.cancel()
            val cancel = received()
            assertEquals(LspConnection.CANCEL_REQUEST, cancel["method"]!!.jsonPrimitive.content)
            assertEquals(id, cancel["params"]!!.jsonObject["id"]!!.jsonPrimitive.long)
        }

    @Test
    fun `requests from the server are answered, unknown ones with an error`() =
        runBlocking<Unit> {
            connect(onRequest = { method, params ->
                if (method != "workspace/configuration") throw unknown(method)
                buildJsonObject { put("asked", params.toString()) }
            })
            val configuration = """{"jsonrpc":"2.0","id":"c1","method":"workspace/configuration","params":{}}"""
            sendToClient(configuration.replace("{}}", """{"items":[]}}"""))
            val answer = received()
            assertEquals("c1", answer["id"]!!.jsonPrimitive.content)
            val asked = answer["result"]!!.jsonObject["asked"]!!.jsonPrimitive
            assertContains(asked.content, "items")
            sendToClient("""{"jsonrpc":"2.0","id":7,"method":"window/unknown"}""")
            val refusal = received()
            assertEquals(7, refusal["id"]!!.jsonPrimitive.int)
            assertEquals(LspConnection.METHOD_NOT_FOUND, refusal["error"]!!.jsonObject["code"]!!.jsonPrimitive.int)
        }

    @Test
    fun `a failing handler still answers`() =
        runBlocking<Unit> {
            connect(onRequest = { _, _ -> error("bug in the handler") })
            sendToClient("""{"jsonrpc":"2.0","id":1,"method":"client/registerCapability"}""")
            val error = received()["error"]!!.jsonObject
            assertEquals(LspConnection.INTERNAL_ERROR, error["code"]!!.jsonPrimitive.int)
        }

    @Test
    fun `notifications from the server reach their handler`() {
        val notified = CompletableFuture<String>()
        connect(onNotification = { method, params -> notified.complete("$method $params") })
        sendToClient("""{"jsonrpc":"2.0","method":"window/logMessage","params":{"message":"hi"}}""")
        assertEquals("""window/logMessage {"message":"hi"}""", notified.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }

    @Test
    fun `the end of the stream fails every pending request`() =
        runBlocking<Unit> {
            val connection = connect()
            val call = scope.async { connection.request("initialize") }
            received()
            serverOutput.close()
            assertFailsWith<LspConnectionClosedException> { withTimeout(TIMEOUT_MS) { call.await() } }
            assertNull(closed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
            assertTrue(connection.isClosed)
            assertFailsWith<LspConnectionClosedException> { connection.request("shutdown") }
        }

    @Test
    fun `invalid JSON closes the connection with a readable reason`() =
        runBlocking<Unit> {
            val connection = connect()
            val call = scope.async { connection.request("initialize") }
            received()
            sendToClient("this is not json")
            val failure =
                assertFailsWith<LspConnectionClosedException> {
                    withTimeout(TIMEOUT_MS) { call.await() }
                }
            assertTrue(failure.message.orEmpty().contains("Invalid JSON"), failure.message)
            assertIs<LspFramingException>(closed.get(TIMEOUT_MS, TimeUnit.MILLISECONDS))
        }

    @Test
    fun `close fails pending requests`() =
        runBlocking<Unit> {
            val connection = connect()
            val call = scope.async { connection.request("initialize") }
            received()
            connection.close()
            assertFailsWith<LspConnectionClosedException> { withTimeout(TIMEOUT_MS) { call.await() } }
        }
}
