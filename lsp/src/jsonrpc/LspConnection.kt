package org.example.kotlineditor.lsp.jsonrpc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/** A JSON-RPC error answer: from the language server to one of our requests, or ours to one of its requests. */
class LspResponseException(
    /** JSON-RPC error code, such as [LspConnection.METHOD_NOT_FOUND]. */
    val code: Int,
    message: String,
    /** The optional `data` member of the error. */
    val data: JsonElement? = null,
) : IOException(message)

/** The connection ended (the server exited, the stream broke, or [LspConnection.close] was called). */
class LspConnectionClosedException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Handles a request the language server sends to us; throw [LspResponseException] to answer with an error. */
typealias LspRequestHandler = suspend (method: String, params: JsonElement?) -> JsonElement

/** Handles a notification the language server sends to us. */
typealias LspNotificationHandler = (method: String, params: JsonElement?) -> Unit

/**
 * JSON-RPC 2.0 over the stdin and stdout of a language server, we being the client.
 *
 * One daemon thread reads the server's messages; writes are serialized. [request] suspends until the answer
 * arrives, and cancelling it sends `$/cancelRequest`. The server's own requests are run on [scope] by
 * [onRequest] (an unknown method answers -32601), its notifications go to [onNotification] on the reader thread.
 * When the stream ends or breaks, every pending request fails with [LspConnectionClosedException] (nobody waits
 * forever) and [onClose] is called once with the reason, null after a clean end or [close].
 */
class LspConnection(
    input: InputStream,
    output: OutputStream,
    private val scope: CoroutineScope,
    private val onRequest: LspRequestHandler = { method, _ -> throw unknownMethod(method) },
    private val onNotification: LspNotificationHandler = { _, _ -> },
    private val onClose: (failure: Throwable?) -> Unit = {},
    private val maxMessageSize: Int = LspFraming.DEFAULT_MAX_MESSAGE_SIZE,
) : Closeable {
    private val input = BufferedInputStream(input)
    private val output = BufferedOutputStream(output)
    private val writeLock = Any()
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonElement>>()
    private val closed = AtomicBoolean()

    @Volatile
    private var closeReason: Throwable? = null

    /** Whether the connection has ended. */
    val isClosed: Boolean get() = closed.get()

    /** Starts reading the server's messages. */
    fun start(): LspConnection {
        thread(name = "lsp-reader", isDaemon = true) { readLoop() }
        return this
    }

    /**
     * Sends a request and suspends until its answer: the `result`, or [LspResponseException] for an error answer,
     * or [LspConnectionClosedException] when the connection ends first.
     */
    suspend fun request(method: String, params: JsonElement? = null): JsonElement {
        val id = ids.incrementAndGet()
        val answer = CompletableDeferred<JsonElement>()
        pending[id] = answer
        try {
            send(message(id = JsonPrimitive(id), method = method, params = params))
            return answer.await()
        } catch (cancelled: CancellationException) {
            if (pending.remove(id) != null && !closed.get()) {
                runCatching { notify(CANCEL_REQUEST, buildJsonObject { put("id", id) }) }
            }
            throw cancelled
        } finally {
            pending.remove(id)
        }
    }

    /** Sends a notification. */
    fun notify(method: String, params: JsonElement? = null) = send(message(method = method, params = params))

    /** Ends the connection: pending requests fail, the streams are closed. */
    override fun close() = finish(null)

    private fun send(message: JsonObject) {
        if (closed.get()) throw closedException()
        try {
            synchronized(writeLock) { LspFraming.write(output, message.toString().encodeToByteArray()) }
        } catch (broken: IOException) {
            finish(broken)
            throw LspConnectionClosedException("Cannot write to the language server: ${broken.message}", broken)
        }
    }

    private fun readLoop() {
        val failure =
            try {
                while (!closed.get()) {
                    val body = LspFraming.read(input, maxMessageSize) ?: break
                    dispatch(parse(body))
                }
                null
            } catch (broken: IOException) {
                broken
            }
        finish(failure)
    }

    private fun dispatch(message: JsonObject) {
        val method = (message[METHOD] as? JsonPrimitive)?.contentOrNull
        val id = message["id"]?.takeUnless { it is JsonNull }
        when {
            method != null && id != null -> scope.launch { answer(id, method, message[PARAMS]) }
            method != null -> onNotification(method, message[PARAMS])
            id != null -> complete(id, message)
        }
    }

    private suspend fun answer(id: JsonElement, method: String, params: JsonElement?) {
        val reply =
            try {
                message(id = id, result = onRequest(method, params))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (refused: LspResponseException) {
                message(id = id, error = error(refused.code, refused.message.orEmpty(), refused.data))
            } catch (
                @Suppress("TooGenericExceptionCaught") failure: Exception,
            ) {
                // A bug in our handler must still answer: a server waiting for us would otherwise stall.
                message(id = id, error = error(INTERNAL_ERROR, failure.message ?: failure.javaClass.simpleName))
            }
        runCatching { send(reply) }
    }

    private fun complete(id: JsonElement, message: JsonObject) {
        val answer = (id as? JsonPrimitive)?.longOrNull?.let(pending::remove) ?: return
        val error = message["error"] as? JsonObject
        if (error == null) {
            answer.complete(message["result"] ?: JsonNull)
        } else {
            val code = (error["code"] as? JsonPrimitive)?.intOrNull ?: INTERNAL_ERROR
            val text = (error["message"] as? JsonPrimitive)?.contentOrNull ?: "Error $code"
            answer.completeExceptionally(LspResponseException(code, text, error["data"]))
        }
    }

    private fun finish(failure: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        closeReason = failure
        val reason = closedException()
        pending.values.forEach { it.completeExceptionally(reason) }
        pending.clear()
        runCatching { output.close() }
        runCatching { input.close() }
        onClose(failure)
    }

    private fun closedException(): LspConnectionClosedException {
        val reason = closeReason
        val message = if (reason == null) "$CONNECTION is closed" else "$CONNECTION broke: ${reason.message}"
        return LspConnectionClosedException(message, reason)
    }

    /** JSON-RPC error codes and the methods of the base protocol. */
    companion object {
        /** The method is unknown. */
        const val METHOD_NOT_FOUND = -32_601

        /** The handler failed. */
        const val INTERNAL_ERROR = -32_603

        /** The request was cancelled by the client (LSP). */
        const val REQUEST_CANCELLED = -32_800

        /** Notification that a request is no longer needed. */
        const val CANCEL_REQUEST = "$/cancelRequest"

        private const val CONNECTION = "The language server connection"
        private const val PARAMS = "params"
        private const val METHOD = "method"

        private fun unknownMethod(method: String) = LspResponseException(METHOD_NOT_FOUND, "Unhandled method $method")

        private fun error(code: Int, message: String, data: JsonElement? = null) =
            buildJsonObject {
                put("code", code)
                put("message", message)
                if (data != null) put("data", data)
            }

        private fun message(
            id: JsonElement? = null,
            method: String? = null,
            params: JsonElement? = null,
            result: JsonElement? = null,
            error: JsonObject? = null,
        ) = buildJsonObject {
            put("jsonrpc", "2.0")
            if (id != null) put("id", id)
            if (method != null) put("method", method)
            if (params != null) put("params", params)
            if (error != null) {
                put("error", error)
            } else if (method == null) {
                put("result", result ?: JsonNull)
            }
        }
    }
}

private fun parse(body: ByteArray): JsonObject =
    try {
        Json.parseToJsonElement(body.decodeToString()).jsonObject
    } catch (invalid: SerializationException) {
        throw LspFramingException("Invalid JSON from the language server: ${invalid.message}", invalid)
    } catch (invalid: IllegalArgumentException) {
        throw LspFramingException("Not a JSON-RPC message: ${invalid.message}", invalid)
    }
