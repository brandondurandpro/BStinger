package org.example.kotlineditor.lsp.jsonrpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.testkit.lsp.FakeLanguageServerCommand
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val ANSWER_MS = 30_000L

/** Our client against a real process misbehaving the way language servers do. */
class FakeLanguageServerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = mutableListOf<Process>()

    private val initializeParams =
        buildJsonObject {
            put("processId", ProcessHandle.current().pid())
            put("rootUri", "file:///work/shop")
            putJsonObject("capabilities") {}
        }

    @AfterTest
    fun cleanup() {
        scope.cancel()
        for (process in processes) {
            val children = process.descendants().toList()
            process.destroyForcibly()
            children.forEach { it.destroyForcibly() }
            process.waitFor(10, TimeUnit.SECONDS)
        }
    }

    private class Started(
        val process: Process,
        val connection: LspConnection,
        val closed: CompletableFuture<Throwable?>,
    ) {
        private val errors = ByteArrayOutputStream()

        init {
            // Always drained: a full stderr pipe blocks the server.
            thread(isDaemon = true) { runCatching { process.errorStream.copyTo(SynchronizedStream(errors)) } }
        }

        fun stderr(): String = synchronized(errors) { errors.toString(Charsets.UTF_8) }

        private class SynchronizedStream(private val target: ByteArrayOutputStream) : OutputStream() {
            override fun write(byte: Int) = synchronized(target) { target.write(byte) }

            override fun write(bytes: ByteArray, offset: Int, length: Int) =
                synchronized(target) {
                    target.write(bytes, offset, length)
                }
        }
    }

    private fun start(vararg options: String, onRequest: LspRequestHandler? = null): Started {
        val process = FakeLanguageServerCommand.start(*options).also { processes += it }
        val closed = CompletableFuture<Throwable?>()
        val connection =
            LspConnection(
                process.inputStream,
                process.outputStream,
                scope,
                onRequest = onRequest ?: { method, params -> refuse(method, params) },
                onClose = { closed.complete(it) },
            ).start()
        return Started(process, connection, closed)
    }

    private fun hoverParams(line: Int) =
        buildJsonObject {
            putJsonObject("textDocument") { put("uri", "file:///work/shop/index.php") }
            putJsonObject("position") {
                put("line", line)
                put("character", 4)
            }
        }

    private fun refuse(method: String, params: JsonElement?): JsonElement =
        throw LspResponseException(LspConnection.METHOD_NOT_FOUND, "$method $params")

    private suspend fun LspConnection.initialize(): JsonElement =
        withTimeout(ANSWER_MS) { request("initialize", initializeParams) }
            .also { notify("initialized", buildJsonObject {}) }

    @Test
    fun `initialize, shutdown and exit end the server cleanly`() =
        runBlocking<Unit> {
            val server = start()
            val result = server.connection.initialize()
            assertEquals(JsonPrimitive(2), result.jsonObject["capabilities"]!!.jsonObject["textDocumentSync"])
            withTimeout(ANSWER_MS) { server.connection.request("shutdown") }
            server.connection.notify("exit")
            assertTrue(server.process.waitFor(ANSWER_MS, TimeUnit.MILLISECONDS))
            assertEquals(0, server.process.exitValue())
            assertEquals(null, server.closed.get(ANSWER_MS, TimeUnit.MILLISECONDS))
        }

    @Test
    fun `a server that crashes fails the pending request instead of hanging`() =
        runBlocking<Unit> {
            val server = start("--crash-after", "2")
            server.connection.initialize()
            val hover = withTimeout(ANSWER_MS) { server.connection.request("textDocument/hover", hoverParams(1)) }
            assertEquals("hover at 1:4", hover.jsonObject["contents"]!!.jsonPrimitive.content)
            assertFailsWith<LspConnectionClosedException> {
                withTimeout(ANSWER_MS) { server.connection.request("textDocument/hover", hoverParams(2)) }
            }
            assertTrue(server.process.waitFor(ANSWER_MS, TimeUnit.MILLISECONDS))
            assertEquals(3, server.process.exitValue())
        }

    @Test
    fun `a server that never answers initialize is timed out by the client`() =
        runBlocking<Unit> {
            val server = start("--hang-on-initialize")
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(2_000) { server.connection.request("initialize", initializeParams) }
            }
            assertTrue(server.process.isAlive)
        }

    @Test
    fun `garbage on stdout closes the connection with a readable error`() =
        runBlocking<Unit> {
            val server = start("--garbage-stdout")
            val failure = assertFailsWith<LspConnectionClosedException> { server.connection.initialize() }
            assertContains(failure.message.orEmpty(), "Not an LSP header")
            assertIs<LspFramingException>(server.closed.get(ANSWER_MS, TimeUnit.MILLISECONDS))
        }

    @Test
    fun `a server flooding stderr keeps answering while stderr is drained`() =
        runBlocking<Unit> {
            val server = start("--stderr-flood")
            server.connection.initialize()
            repeat(50) { line ->
                withTimeout(ANSWER_MS) { server.connection.request("textDocument/hover", hoverParams(line)) }
            }
            assertTrue(server.stderr().length > 64 * 1024)
        }

    @Test
    fun `a huge answer is refused by the size cap without running out of memory`() =
        runBlocking<Unit> {
            val server = start("--huge-message", "80")
            server.connection.initialize()
            val failure =
                assertFailsWith<LspConnectionClosedException> {
                    withTimeout(ANSWER_MS) { server.connection.request("textDocument/hover", hoverParams(0)) }
                }
            assertContains(failure.message.orEmpty(), "larger than")
        }

    @Test
    fun `requests from the server are answered`() =
        runBlocking<Unit> {
            val server =
                start("--ask-configuration") { method, _ ->
                    assertEquals("workspace/configuration", method)
                    buildJsonArray { add(buildJsonObject { put("maxMemory", 2048) }) }
                }
            server.connection.initialize()
            val deadline = System.nanoTime() + ANSWER_MS * 1_000_000
            while (!server.stderr().contains("maxMemory") && System.nanoTime() < deadline) Thread.onSpinWait()
            assertContains(server.stderr(), """response "configuration-1": [{"maxMemory":2048}]""")
        }

    @Test
    fun `diagnostics are published for an opened document`() =
        runBlocking<Unit> {
            val published = CompletableFuture<String>()
            val process = FakeLanguageServerCommand.start("--diagnostics").also { processes += it }
            val connection =
                LspConnection(
                    process.inputStream,
                    process.outputStream,
                    scope,
                    onNotification = { method, params ->
                        if (method == "textDocument/publishDiagnostics") published.complete(params.toString())
                    },
                ).start()
            thread(isDaemon = true) { runCatching { process.errorStream.transferTo(OutputStream.nullOutputStream()) } }
            connection.initialize()
            connection.notify(
                "textDocument/didOpen",
                buildJsonObject {
                    putJsonObject("textDocument") {
                        put("uri", "file:///work/shop/index.php")
                        put("languageId", "php")
                        put("version", 1)
                        put("text", "<?php\n")
                    }
                },
            )
            assertContains(published.get(ANSWER_MS, TimeUnit.MILLISECONDS), "fake error")
        }
}
