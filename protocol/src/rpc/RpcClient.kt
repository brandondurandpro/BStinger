package org.example.kotlineditor.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.NetworkInspector
import org.example.kotlineditor.model.OutcomeUnknownException
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.telemetry.Attribute
import org.example.kotlineditor.telemetry.Attributes
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.CurrentSpan
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.Outcome
import org.example.kotlineditor.telemetry.Span
import org.example.kotlineditor.telemetry.Telemetry
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicLong

/** Network tuning of a [RpcClient]. */
class ClientOptions(
    /** Maximum time to establish the TCP connection. */
    val connectTimeoutMs: Int = 5_000,
    /** A ping is sent when nothing was sent for this long, so a silent dead link is noticed. */
    val heartbeatMs: Long = 3_000,
    /** Without any frame received for this long the connection is considered dead and re-established. */
    val deadAfterMs: Int = 10_000,
    /** A request still unanswered after this long fails (it is retried across reconnections until then). */
    val callTimeoutMs: Long = 120_000,
    /** Longest wait between two connection attempts (the wait doubles from [MIN_BACKOFF_MS]). */
    val maxBackoffMs: Long = 3_000,
) {
    /** Defaults shared by every client. */
    companion object {
        /** First wait before retrying a failed connection. */
        const val MIN_BACKOFF_MS = 50L
    }
}

/** Thrown when the server refuses the client (bad token or protocol version): retrying would not help. */
class RejectedException(message: String) : IOException(message)

// The outcome of a request the server answered with an error.
private const val REMOTE_ERROR = "RemoteException"

/**
 * Client side of the protocol: one TCP connection, multiplexed requests, automatic reconnection.
 *
 * - A call never writes on its caller's thread: its request is queued, and a writer on [Dispatchers.IO] encodes,
 *   compresses and writes it (see [Outbox]).
 * - Requests are registered before being sent and stay registered until answered: when the link breaks they
 *   are simply sent again on the next connection, with the same id (the server deduplicates). When the server
 *   restarted meanwhile it cannot: a mutation already sent then fails with [OutcomeUnknownException] instead.
 * - Streams (shell output) carry sequence numbers; out-of-order events are buffered, duplicates dropped, and
 *   after a reconnection the client asks the server to replay what it missed.
 */
@Suppress("TooManyFunctions") // one stream of each kind on top of the plumbing of the connection
class RpcClient(
    host: String,
    port: Int,
    token: String,
    private val options: ClientOptions = ClientOptions(),
    private val telemetry: Telemetry = ClientTelemetry,
) : Closeable {
    /** Scope of the client's background work, cancelled by [close]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val pending = PendingRequests()
    private val outbox = Outbox(pending, options.heartbeatMs)
    private val streams = StreamRegistry()
    private val mutableStatus = MutableStateFlow(ConnectionStatus.Reconnecting)

    /** Capture of the frames exchanged with the server, off until the devtools turn it on. */
    val network = NetworkInspector()

    private val connector =
        Connector(
            InetSocketAddress(host, port),
            token,
            options,
            pending,
            outbox,
            streams,
            mutableStatus,
            ::measureReconnections,
            NetworkTap(network),
        ) { toResume ->
            scope.launch {
                runCatching {
                    if (toResume.search) {
                        call(Method.FindInFilesResume) {
                            put("stream", toResume.id)
                            put("lastSeq", toResume.lastSeq)
                        }
                    } else {
                        call(Method.TerminalResume) {
                            put("terminal", toResume.id)
                            put("lastSeq", toResume.lastSeq)
                        }
                    }
                }
            }
        }

    /** State of the link with the server. */
    val status: StateFlow<ConnectionStatus> = mutableStatus.asStateFlow()

    /**
     * Number of connections opened so far, one more at each reconnection: unlike [status], which may be back to
     * Connected before a collector saw it leave, every reconnection changes it.
     */
    val connections: StateFlow<Long> = connector.connections.asStateFlow()

    // Only touched by the connector's loop, which reports every status change in order.
    private var connectedOnce = false
    private var lost: Span? = null

    init {
        scope.launch { connector.run(this) }
    }

    // connection.reconnect: from the link lost to the link back (the first connection is not a reconnection).
    private fun measureReconnections(now: ConnectionStatus) {
        when (now) {
            ConnectionStatus.Connected -> {
                telemetry.end(lost)
                lost = null
                connectedOnce = true
            }

            ConnectionStatus.Reconnecting -> {
                if (connectedOnce && lost == null) lost = telemetry.start(Operation.Reconnect)
            }

            ConnectionStatus.Disconnected -> {
                telemetry.end(lost, Outcome.Cancelled)
                lost = null
            }
        }
    }

    /** A new request id; ids increase, which keeps retried requests in their original order. */
    fun newId(): Long = nextId.getAndIncrement()

    /** Suspends until the client is connected, failing after [timeoutMs]. */
    suspend fun awaitConnected(timeoutMs: Long = options.callTimeoutMs) {
        withTimeout(timeoutMs) { status.first { it == ConnectionStatus.Connected } }
    }

    /** Sends [method] with the named params built by [params], and waits for its result, surviving reconnections. */
    suspend fun call(
        method: Method,
        id: Long = newId(),
        timeoutMs: Long = options.callTimeoutMs,
        params: JsonObjectBuilder.() -> Unit = {},
    ): JsonElement {
        val response = exchange(method, id, timeoutMs, params)
        if (response.kind == FrameKind.Error) throw RemoteException(response.payload.errorMessage())
        return response.payload
    }

    /** Registers the stream fed by the server under [id] (before the request creating it is sent). */
    fun openStream(id: Long): Channel<TerminalSnapshot> = streams.open(id)

    /** Registers the stream of Find in Files batches of [id], like [openStream]. */
    fun openSearchStream(id: Long): Channel<SearchBatch> = streams.openSearch(id)

    /** Stops listening to the stream [id]. */
    fun closeStream(id: Long) = streams.close(id)

    /**
     * The server now expects [token] (it restarted with a new secret): the next connections use it, and a client
     * the server refused with the old one starts connecting again.
     */
    fun reconnectWith(token: String) {
        if (connector.retryWith(token) && scope.isActive) {
            mutableStatus.value = ConnectionStatus.Reconnecting
            scope.launch { connector.run(this) }
        }
    }

    override fun close() {
        connector.close()
        streams.closeAll()
        pending.failAll(IOException("Client closed"))
        mutableStatus.value = ConnectionStatus.Disconnected
        scope.cancel()
    }

    private suspend fun exchange(
        method: Method,
        id: Long,
        timeoutMs: Long,
        params: JsonObjectBuilder.() -> Unit,
    ): Frame {
        connector.rejection?.let { throw it }
        val span = startSpan(method)
        val call =
            pending.register(
                Frame(
                    FrameKind.Request,
                    id,
                    buildJsonObject(params),
                    method,
                    code = method == Method.Write || method == Method.CreateFile,
                    trace = span?.context?.traceparent(),
                ),
            )
        // Written by the outbox's writer, off this thread (the UI's, often), on the current connection or the next.
        outbox.request(call)
        var outcome: Outcome = Outcome.Ok
        try {
            val answer = awaitAnswer(call, method, timeoutMs)
            if (answer.kind == FrameKind.Error) outcome = Outcome.Error(REMOTE_ERROR)
            return answer
        } catch (@Suppress("TooGenericExceptionCaught") failure: Throwable) {
            // measured, then thrown on
            outcome = Outcome.of(failure)
            throw failure
        } finally {
            val written = call.abandon()
            pending.remove(id)
            // Timed out or cancelled once sent: the server can stop working on it.
            if (written && !call.result.isCompleted) outbox.notify(Frame(FrameKind.Cancel, id))
            telemetry.end(span, outcome, retriedOf(call, span))
        }
    }

    private suspend fun awaitAnswer(call: PendingRequests.Pending, method: Method, timeoutMs: Long): Frame =
        withTimeoutOrNull(timeoutMs) { call.result.await() }
            ?: throw IOException("No answer from the server for $method after ${timeoutMs}ms")

    // The rpc.client span of a call, child of the span of the calling coroutine; null when telemetry is off.
    private suspend fun startSpan(method: Method): Span? {
        if (!telemetry.isEnabled) return null
        val parent = CurrentSpan.ofCoroutine()?.context
        return telemetry.start(Operation.RpcClient, Attributes.of(Attribute.Method to method.rpcName), parent)
    }
}

// Whether the request was sent again on a new connection; nothing to say when it is not measured.
private fun retriedOf(call: PendingRequests.Pending, span: Span?): Attributes {
    if (span == null) return Attributes.NONE
    val retried = call.sends > 1
    return Attributes.of(Attribute.Retried to retried.toString())
}
