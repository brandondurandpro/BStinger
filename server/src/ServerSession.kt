package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.InvalidParamsException
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.params
import org.example.kotlineditor.rpc.rpcError
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// Methods run on the reading thread, in arrival order: they are quick and their order matters.
private val ORDERED =
    setOf(
        Method.TerminalStart,
        Method.TerminalWrite,
        Method.TerminalKey,
        Method.TerminalPaste,
        Method.TerminalResize,
        Method.TerminalScroll,
        Method.TerminalClear,
        Method.TerminalDestroy,
        Method.FindInFiles,
        Method.StreamResume,
        Method.StreamClose,
    )

/** One request being executed: its id, its named params, and the session's terminals and searches. */
internal class Request(
    val id: Long,
    val params: JsonObject,
    val terminals: TerminalRegistry,
    val searches: SearchStreams,
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

/** A client across reconnections: in-flight requests, cached answers of mutations, running terminals and searches. */
internal class ServerSession(
    val id: String,
    private val scope: CoroutineScope,
    private val handlers: Map<Method, suspend Request.() -> JsonElement>,
    private val options: ServerOptions,
) {
    @Volatile
    var connection: ServerConnection? = null
        private set

    @Volatile
    var detachedAt = 0L
        private set

    private val inFlight = ConcurrentHashMap<Long, Job>()
    private val answers =
        object : LinkedHashMap<Long, Frame>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Frame>?) =
                size > options.responseCacheSize
        }
    private val terminals = TerminalRegistry(::send)
    private val searches = SearchStreams(scope, ::send)

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
            FrameKind.Cancel -> inFlight.remove(frame.id)?.cancel()
            FrameKind.Request -> onRequest(frame)
            else -> Unit
        }
    }

    fun dispose() {
        inFlight.values.forEach { it.cancel() }
        terminals.stopAll()
        searches.closeAll()
        connection?.close()
    }

    // Lost when the client is disconnected: it will retry the request or resume the stream.
    private fun send(frame: Frame) {
        connection?.send(frame)
    }

    private fun onRequest(frame: Frame) {
        val cached = synchronized(answers) { answers[frame.id] }
        val method = frame.method
        when {
            cached != null -> send(cached)

            // a retry of an executed request: same answer, no second execution
            method == null -> send(failure(frame.id, "Unknown method", Protocol.METHOD_NOT_FOUND))

            method in ORDERED -> complete(frame.id, method, runBlocking { execute(frame.id, method, frame.payload) })

            else -> launchRequest(frame.id, method, frame.payload)
        }
    }

    private fun launchRequest(id: Long, method: Method, params: JsonElement) {
        val job = scope.launch(start = CoroutineStart.LAZY) { complete(id, method, execute(id, method, params)) }
        // Already running (sent again after a reconnection): its answer will be sent when it completes.
        if (inFlight.putIfAbsent(id, job) == null) job.start() else job.cancel()
    }

    private fun complete(id: Long, method: Method, answer: Frame) {
        if (method.mutating) synchronized(answers) { answers[id] = answer }
        inFlight.remove(id)
        send(answer)
    }

    private suspend fun execute(id: Long, method: Method, params: JsonElement): Frame =
        try {
            val request = Request(id, params.params(), terminals, searches)
            val result = checkNotNull(handlers[method]) { "No handler for $method" }.invoke(request)
            Frame(FrameKind.Response, id, result, code = request.code)
        } catch (e: IOException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        } catch (e: InvalidParamsException) {
            failure(id, e.message.orEmpty(), Protocol.INVALID_PARAMS)
        } catch (e: IllegalArgumentException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        } catch (e: IllegalStateException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        } catch (e: SecurityException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        }

    private fun failure(id: Long, message: String, code: Int = Protocol.SERVER_ERROR) =
        Frame(FrameKind.Error, id, rpcError(code, message))
}
