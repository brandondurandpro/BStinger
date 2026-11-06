package org.example.kotlineditor.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.ConnectionStatus
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Keeps one live connection to the server: connects, authenticates, has the [outbox] resend what is pending and send
 * the heartbeats, resumes streams, and starts over (with an increasing delay) whenever the link breaks. When the server
 * lost the session (it restarted), the mutations already sent fail instead of being sent again.
 */
internal class Connector(
    private val address: InetSocketAddress,
    token: String,
    private val options: ClientOptions,
    private val pending: PendingRequests,
    private val outbox: Outbox,
    private val streams: StreamRegistry,
    private val status: MutableStateFlow<ConnectionStatus>,
    private val onStatus: (ConnectionStatus) -> Unit,
    private val tap: NetworkTap,
    private val resume: (StreamRegistry.Resume) -> Unit,
) {
    /** Number of connections opened so far: one more at each reconnection. */
    val connections = MutableStateFlow(0L)

    @Volatile var rejection: RejectedException? = null
        private set

    // Sent at each handshake; it changes when the server comes back with a new secret (see retryWith).
    @Volatile private var token: String = token

    @Volatile private var sessionId: String? = null

    @Volatile private var closed = false

    // The link being served, closed by [close].
    @Volatile private var current: Link? = null

    // Every change, as it happens: [status] conflates, a collector may miss a reconnection that came back quickly.
    private fun report(now: ConnectionStatus) {
        status.value = now
        onStatus(now)
    }

    // Neither closed nor rejected by the server, and still wanted.
    private fun canRetry(scope: CoroutineScope): Boolean {
        val open = !closed && rejection == null
        return open && scope.isActive
    }

    suspend fun run(scope: CoroutineScope) {
        var backoff = ClientOptions.MIN_BACKOFF_MS
        while (canRetry(scope)) {
            val opened = tryOpen()
            if (opened == null) {
                if (rejection == null) report(ConnectionStatus.Reconnecting)
                delay(backoff)
                backoff = minOf(backoff * 2, options.maxBackoffMs)
            } else {
                backoff = ClientOptions.MIN_BACKOFF_MS
                serve(opened)
            }
        }
    }

    /**
     * Uses [newToken] from the next handshake on. Returns true when the server had refused the previous one: the
     * caller must then [run] the connector again, as a refusal ends it.
     */
    fun retryWith(newToken: String): Boolean {
        token = newToken
        val refused = rejection != null
        rejection = null
        return refused
    }

    fun close() {
        closed = true
        current?.close()
    }

    private suspend fun tryOpen(): Link? =
        try {
            runInterruptible { openLink() }
        } catch (rejected: RejectedException) {
            rejection = rejected
            pending.failAll(rejected)
            report(ConnectionStatus.Disconnected)
            null
        } catch (expected: IOException) {
            null // server unreachable for now: retried after a backoff
        }

    private suspend fun serve(opened: Link) {
        current = opened
        if (closed) opened.close()
        report(ConnectionStatus.Connected)
        val connection = connections.updateAndGet { it + 1 }
        try {
            streams.resumes().forEach { resume(it) }
            watch(opened, connection)
        } catch (expected: IOException) {
            // Link lost (closed, reset, or silent for too long): the loop reconnects.
        } finally {
            current = null
            opened.close()
            if (!closed) report(ConnectionStatus.Reconnecting)
        }
    }

    private fun openLink(): Link {
        val socket = Socket()
        try {
            socket.tcpNoDelay = true // latency matters more than bandwidth for small requests
            socket.keepAlive = true
            socket.connect(address, options.connectTimeoutMs)
            socket.soTimeout = options.deadAfterMs
            val link = Link(socket, tap)
            handshake(link)
            return link
        } catch (failure: IOException) {
            socket.close()
            throw failure
        }
    }

    private fun handshake(link: Link) {
        link.send(
            Frame(
                FrameKind.Hello,
                Protocol.HELLO_ID,
                buildJsonObject {
                    put("version", Protocol.VERSION)
                    put("token", token)
                    put("session", sessionId)
                },
            ),
        )
        val answer = link.read()
        when (answer.kind) {
            FrameKind.Welcome -> {
                val welcome = answer.payload.jsonObject
                val id = welcome.string("session")
                val resumed = welcome.bool("resumed")
                if (!resumed && sessionId != null) {
                    // A new session: the server restarted or forgot this client, and with it what it ran for it.
                    streams.loseAll()
                    pending.failSentMutations()
                }
                sessionId = id
            }

            FrameKind.Reject -> {
                throw RejectedException(answer.payload.errorMessage())
            }

            else -> {
                throw IOException("Unexpected handshake answer ${answer.kind}")
            }
        }
    }

    private suspend fun watch(link: Link, connection: Long) =
        coroutineScope {
            // Sends the pending requests again, then what is queued, and the heartbeats.
            val writer =
                launch {
                    try {
                        outbox.drain(link, connection)
                    } finally {
                        link.close() // the reader below fails at once and the loop reconnects
                    }
                }
            try {
                // Fails with SocketTimeoutException after deadAfterMs of silence: the link is dead.
                runInterruptible { while (true) dispatch(link.read()) }
            } finally {
                // Unblocks a write in progress, then stops the writer.
                link.close()
                writer.cancel()
            }
        }

    private fun dispatch(frame: Frame) {
        when (frame.kind) {
            FrameKind.Response, FrameKind.Error -> pending.complete(frame)
            FrameKind.Event -> streams.onEvent(frame.id, frame.payload)
            FrameKind.End -> streams.onEnd(frame.id, frame.payload)
            FrameKind.Ping -> outbox.notify(Frame(FrameKind.Pong, 0))
            else -> Unit // Pong: receiving it was the point
        }
    }
}
