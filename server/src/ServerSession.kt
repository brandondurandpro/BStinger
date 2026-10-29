package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.WireReader
import org.example.kotlineditor.rpc.WireWriter
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// Methods run on the reading thread, in arrival order: they are quick and their order matters.
private val ORDERED = setOf(Method.ShellSend, Method.ShellDestroy, Method.ShellResume)

/** One request being executed: its id, its arguments, where to write the answer, and the session's shells. */
internal class Request(val id: Long, val args: WireReader, val answer: WireWriter, val shells: ShellRegistry)

/** Shells of a session, keyed by the id of their output stream. */
internal class ShellRegistry(private val backlog: Int, private val push: (Frame) -> Unit) {
    private val shells = ConcurrentHashMap<Long, ServerShell>()

    fun start(id: Long, directory: String) {
        shells[id] = ServerShell(id, directory, backlog, push)
    }

    operator fun get(id: Long): ServerShell? = shells[id]

    fun stop(id: Long) {
        shells.remove(id)?.destroy()
    }

    fun stopAll() {
        shells.values.forEach { it.destroy() }
        shells.clear()
    }
}

/** A client across reconnections: in-flight requests, cached answers of mutations, running shells. */
internal class ServerSession(
    val id: String,
    private val scope: CoroutineScope,
    private val handlers: Map<Method, suspend Request.() -> Unit>,
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
    private val shells = ShellRegistry(options.streamBacklog, ::send)

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
        shells.stopAll()
        connection?.close()
    }

    // Lost when the client is disconnected: it will retry the request or resume the stream.
    private fun send(frame: Frame) {
        connection?.send(frame)
    }

    private fun onRequest(frame: Frame) {
        val cached = synchronized(answers) { answers[frame.id] }
        val reader = WireReader(frame.body)
        val method = Method.entries.getOrNull(reader.short())
        when {
            cached != null -> send(cached)

            // a retry of an executed request: same answer, no second execution
            method == null -> send(failure(frame.id, "Unknown method"))

            method in ORDERED -> complete(frame.id, method, runBlocking { execute(frame.id, method, reader) })

            else -> launchRequest(frame.id, method, reader)
        }
    }

    private fun launchRequest(id: Long, method: Method, reader: WireReader) {
        val job = scope.launch(start = CoroutineStart.LAZY) { complete(id, method, execute(id, method, reader)) }
        // Already running (sent again after a reconnection): its answer will be sent when it completes.
        if (inFlight.putIfAbsent(id, job) == null) job.start() else job.cancel()
    }

    private fun complete(id: Long, method: Method, answer: Frame) {
        if (method.mutating) synchronized(answers) { answers[id] = answer }
        inFlight.remove(id)
        send(answer)
    }

    private suspend fun execute(id: Long, method: Method, reader: WireReader): Frame {
        val request = Request(id, reader, WireWriter(), shells)
        return try {
            checkNotNull(handlers[method]) { "No handler for $method" }.invoke(request)
            Frame(FrameKind.Response, id, request.answer.toByteArray())
        } catch (e: IOException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        } catch (e: IllegalArgumentException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        } catch (e: IllegalStateException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        } catch (e: SecurityException) {
            failure(id, e.message ?: e::class.simpleName.orEmpty())
        }
    }

    private fun failure(id: Long, message: String) =
        Frame(FrameKind.Error, id, WireWriter().string(message).toByteArray())
}
