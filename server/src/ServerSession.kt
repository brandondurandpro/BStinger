package org.example.kotlineditor.server

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.InvalidParamsException
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.params
import org.example.kotlineditor.rpc.rpcError
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.telemetry.SpanContext
import org.example.kotlineditor.telemetry.SpanElement
import org.example.kotlineditor.telemetry.Telemetry
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// The outcome of a request answered with an error.
private const val REMOTE_ERROR = "RemoteError"

// Methods whose order matters: each runs after the previous request of its key (see [orderingKey]) has finished,
// on a coroutine, never on the reading thread.
private val TERMINAL_METHODS =
    setOf(
        Method.TerminalWrite,
        Method.TerminalKey,
        Method.TerminalPaste,
        Method.TerminalResize,
        Method.TerminalScroll,
        Method.TerminalClear,
        Method.TerminalResume,
        Method.TerminalDestroy,
    )

// The edits of a document apply in the order they were typed.
private val DOCUMENT_METHODS = setOf(Method.DocOpen, Method.DocChange, Method.DocClose)

private const val TERMINAL = "terminal"
private const val PATH = "path"

// The queue of a terminal's commands; terminal/start names its terminal by its own request id.
private fun terminalKey(terminal: Long?) = "terminal:$terminal"

private fun documentKey(path: String) = "doc:$path"

// Each lsp/use replaces the files declared by the previous one: they must apply in order.
private const val LSP_USE_KEY = "lsp/use"

private fun JsonElement.namedOrNull(name: String): JsonPrimitive? {
    val value = (this as? JsonObject)?.get(name)
    return value as? JsonPrimitive
}

// The path of the document a request is about, for the requests that name one.
private fun documentPathOf(frame: Frame): String? {
    val path = frame.payload.namedOrNull(PATH)
    return path?.takeIf { it.isString }?.content
}

// The sequential queue a method runs in, null for the methods that run concurrently. A request of a malformed key
// still gets its family's queue, so its order is kept until its handler rejects its params.
private fun orderingKey(method: Method, frame: Frame): String? =
    when (method) {
        Method.TerminalStart -> terminalKey(frame.id)
        in TERMINAL_METHODS -> terminalKey(frame.payload.namedOrNull(TERMINAL)?.longOrNull)
        in DOCUMENT_METHODS -> documentKey(documentPathOf(frame).orEmpty())
        Method.LspUse -> LSP_USE_KEY
        else -> null
    }

/** A request being executed: [cancellable] by the client, unless it is one of a sequential queue. */
private class Running(val job: Job, val cancellable: Boolean)

// Only the entry of the job [self]: a retry dispatched once it was released has its own.
private fun ConcurrentHashMap<Long, Running>.release(id: Long, self: Job) {
    computeIfPresent(id) { _, running -> running.takeIf { it.job !== self } }
}

/** One request being executed: its id, its named params, the session's terminals and the session's id. */
internal class Request(
    val id: Long,
    val params: JsonObject,
    val terminals: TerminalRegistry,
    val session: String = "",
) {
    /** Set by a handler whose answer is source code, so the codec may use the code dictionary. */
    var code = false
}

/** Terminals of a session, keyed by the id of their snapshot stream. */
internal class TerminalRegistry(private val push: (Frame) -> Unit) {
    private val terminals = ConcurrentHashMap<Long, ServerTerminal>()

    fun start(id: Long, directory: String, columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        terminals.remove(id)?.destroy()
        terminals[id] =
            ServerTerminal(id, directory, columns, rows, cellWidthPx, cellHeightPx, push)
    }

    operator fun get(id: Long): ServerTerminal? = terminals[id]

    fun stop(id: Long) {
        terminals.remove(id)?.destroy()
    }

    fun stopAll() {
        terminals.values.forEach { it.destroy() }
        terminals.clear()
    }
}

/** A client across reconnections: in-flight requests, cached answers of mutations, running terminals. */
internal class ServerSession(
    val id: String,
    private val scope: CoroutineScope,
    private val handlers: Map<Method, suspend Request.() -> JsonElement>,
    private val options: ServerOptions,
    private val telemetry: Telemetry = Telemetry.Disabled,
    private val onDispose: (String) -> Unit = {},
) {
    @Volatile
    var connection: ServerConnection? = null
        private set

    @Volatile
    var detachedAt = 0L
        private set

    private val inFlight = ConcurrentHashMap<Long, Running>()

    // The last request of each sequential queue: the next one of the key waits for it.
    private val queues = ConcurrentHashMap<String, Job>()
    private val dispatching = Any()
    private val answers =
        object : LinkedHashMap<Long, Frame>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Frame>?) =
                size > options.responseCacheSize
        }
    private val terminals = TerminalRegistry(::send)

    fun attach(new: ServerConnection) {
        val old = connection
        connection = new
        if (old !== new) old?.close()
    }

    fun detach(lost: ServerConnection) {
        if (connection === lost) {
            connection = null
            detachedAt = System.currentTimeMillis()
        }
    }

    fun onFrame(from: ServerConnection, frame: Frame) {
        when (frame.kind) {
            FrameKind.Ping -> from.send(Frame(FrameKind.Pong, 0))
            FrameKind.Cancel -> cancel(frame.id)
            FrameKind.Request -> onRequest(frame)
            else -> Unit
        }
    }

    fun dispose() {
        inFlight.values.forEach { it.job.cancel() }
        terminals.stopAll()
        connection?.close()
        onDispose(id)
    }

    // Lost when the client is disconnected: it will retry the request or resume the stream.
    private fun send(frame: Frame) {
        connection?.send(frame)
    }

    // A request of a sequential queue always runs: the requests after it in its queue expect its effect.
    private fun cancel(id: Long) {
        val running = inFlight[id]?.takeIf { it.cancellable } ?: return
        if (inFlight.remove(id, running)) running.job.cancel()
    }

    private fun onRequest(frame: Frame) {
        val cached = synchronized(answers) { answers[frame.id] }
        val method = frame.method
        when {
            // a retry of an executed request: same answer, no second execution
            cached != null -> send(cached)

            method == null -> send(failure(frame.id, "Unknown method", Protocol.METHOD_NOT_FOUND))

            else -> dispatch(frame, method)
        }
    }

    // Runs the request on a coroutine: the reading thread only dispatches. A request of a sequential queue starts
    // once the previous one of its key finished; a request that names a document starts once the edits of that
    // document received before it applied, as when they ran on the reading thread.
    private fun dispatch(frame: Frame, method: Method) {
        synchronized(dispatching) {
            val id = frame.id
            // Already running (sent again after a reconnection): its answer will be sent when it completes.
            if (inFlight.containsKey(id)) return
            val key = orderingKey(method, frame)
            val documentQueue = documentPathOf(frame)?.let { queues[documentKey(it)] }
            val previous = key?.let { queues[it] } ?: documentQueue
            val job =
                scope.launch(start = CoroutineStart.LAZY) {
                    val self = coroutineContext.job
                    try {
                        previous?.join()
                        complete(id, method, measured(frame, method), self)
                    } finally {
                        inFlight.release(id, self)
                        key?.let { queues.remove(it, self) }
                    }
                }
            inFlight[id] = Running(job, cancellable = key == null)
            key?.let { queues[it] = job }
            job.start()
        }
    }

    // Cached, then no longer running, then sent: a retry sent as soon as the answer arrived finds it cached, or runs
    // again, instead of being dropped as still running.
    private fun complete(id: Long, method: Method, answer: Frame, self: Job) {
        if (method.mutating) synchronized(answers) { answers[id] = answer }
        inFlight.release(id, self)
        send(answer)
    }

    // The rpc.server span of a request, child of the client's span named by its traceparent. A retry of a request
    // already answered or running is not executed again, so it is not measured twice.
    private suspend fun measured(frame: Frame, method: Method): Frame {
        if (!telemetry.isEnabled) return execute(frame.id, method, frame.payload)
        val attributes = Attributes.of(Attribute.Method to method.rpcName)
        val span = telemetry.start(Operation.RpcServer, attributes, SpanContext.parse(frame.trace))
        var outcome: Outcome = Outcome.Ok
        try {
            val answer =
                if (span == null) {
                    execute(frame.id, method, frame.payload)
                } else {
                    withContext(SpanElement(span)) { execute(frame.id, method, frame.payload) }
                }
            if (answer.kind == FrameKind.Error) outcome = Outcome.Error(REMOTE_ERROR)
            return answer
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // measured, then thrown on
            outcome = Outcome.of(failure)
            throw failure
        } finally {
            telemetry.end(span, outcome)
        }
    }

    private suspend fun execute(id: Long, method: Method, params: JsonElement): Frame =
        try {
            val request = Request(id, params.params(), terminals, this.id)
            val result = checkNotNull(handlers[method]) { "No handler for $method" }.invoke(request)
            Frame(FrameKind.Response, id, result, code = request.code)
        } catch (e: IOException) {
            failure(id, e.text())
        } catch (e: InvalidParamsException) {
            failure(id, e.message.orEmpty(), Protocol.INVALID_PARAMS)
        } catch (e: IllegalArgumentException) {
            failure(id, e.text())
        } catch (e: CancellationException) {
            // Cancelled (by the client, or the session ended): no answer. A timeout inside the handler is a failure.
            currentCoroutineContext().ensureActive()
            failure(id, e.text())
        } catch (e: IllegalStateException) {
            failure(id, e.text())
        } catch (e: SecurityException) {
            failure(id, e.text())
        } catch (@Suppress("TooGenericExceptionCaught") e: Throwable) {
            // A bug of a handler: answered, so that the client does not wait for its timeout and can retry.
            unexpected(id, method, e)
        }
}

private fun Throwable.text(): String = message ?: this::class.simpleName.orEmpty()

private fun unexpected(id: Long, method: Method, failure: Throwable): Frame {
    System.err.println("Request ${method.rpcName} failed unexpectedly: ${failure.stackTraceToString()}")
    val message = "Internal error in ${method.rpcName}: ${failure.text()}"
    return failure(id, message, Protocol.INTERNAL_ERROR)
}

private fun failure(id: Long, message: String, code: Int = Protocol.SERVER_ERROR) =
    Frame(FrameKind.Error, id, rpcError(code, message))
