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
import org.example.kotlineditor.model.TerminalSnapshot
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

/**
 * Client side of the protocol: one TCP connection, multiplexed requests, automatic reconnection.
 *
 * - Requests are registered before being sent and stay registered until answered: when the link breaks they
 *   are simply sent again on the next connection, with the same id (the server deduplicates).
 * - Streams (shell output) carry sequence numbers; out-of-order events are buffered, duplicates dropped, and
 *   after a reconnection the client asks the server to replay what it missed.
 */
class RpcClient(host: String, port: Int, token: String, private val options: ClientOptions = ClientOptions()) :
    Closeable {
    /** Scope of the client's background work, cancelled by [close]. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val nextId = AtomicLong(1)
    private val pending = PendingRequests()
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
            streams,
            mutableStatus,
            NetworkTap(network),
        ) { id, lastSeq ->
            scope.launch {
                runCatching {
                    call(Method.TerminalResume) {
                        put("terminal", id)
                        put("lastSeq", lastSeq)
                    }
                }
            }
        }

    /** State of the link with the server. */
    val status: StateFlow<ConnectionStatus> = mutableStatus.asStateFlow()

    /** Number of connections opened so far (tests use it to check reconnections). */
    val connections: AtomicLong get() = connector.connections

    init {
        scope.launch { connector.run(this) }
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
        val call =
            pending.register(
                Frame(
                    FrameKind.Request,
                    id,
                    buildJsonObject(params),
                    method,
                    code = method == Method.Write || method == Method.CreateFile,
                ),
            )
        try {
            connector.link?.send(call.frame) // otherwise sent as soon as a connection is established
            return withTimeoutOrNull(timeoutMs) { call.result.await() }
                ?: throw IOException("No answer from the server for $method after ${timeoutMs}ms")
        } finally {
            pending.remove(id)
            // Timed out or cancelled: the server can stop working on it.
            if (!call.result.isCompleted) connector.link?.send(Frame(FrameKind.Cancel, id))
        }
    }
}
