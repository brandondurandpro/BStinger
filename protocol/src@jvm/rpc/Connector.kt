package org.example.kotlineditor.rpc

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
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
import java.util.concurrent.atomic.AtomicLong

/**
 * Keeps one live connection to the server: connects, authenticates, resends what is pending, resumes streams,
 * watches the link with heartbeats, and starts over (with an increasing delay) whenever the link breaks.
 */
internal class Connector(
    private val address: InetSocketAddress,
    token: String,
    private val options: ClientOptions,
    private val pending: PendingRequests,
    private val streams: StreamRegistry,
    private val status: MutableStateFlow<ConnectionStatus>,
    private val tap: NetworkTap,
    private val resume: (streamId: Long, lastSeq: Long) -> Unit,
) {
    val connections = AtomicLong(0)

    @Volatile var link: Link? = null
        private set

    @Volatile var rejection: RejectedException? = null
        private set

    // Sent at each handshake; it changes when the server comes back with a new secret (see retryWith).
    @Volatile private var token: String = token

    @Volatile private var sessionId: String? = null

    @Volatile private var closed = false

    suspend fun run(scope: CoroutineScope) {
        var backoff = ClientOptions.MIN_BACKOFF_MS
        while (!closed && scope.isActive && rejection == null) {
            val opened = tryOpen()
            if (opened == null) {
                if (rejection == null) status.value = ConnectionStatus.Reconnecting
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
        link?.close()
    }

    private suspend fun tryOpen(): Link? =
        try {
            runInterruptible { openLink() }
        } catch (rejected: RejectedException) {
            rejection = rejected
            pending.failAll(rejected)
            status.value = ConnectionStatus.Disconnected
            null
        } catch (expected: IOException) {
            null // server unreachable for now: retried after a backoff
        }

    private suspend fun serve(opened: Link) {
        connections.incrementAndGet()
        link = opened
        status.value = ConnectionStatus.Connected
        try {
            // Unanswered requests, in their original order: the server deduplicates retries.
            pending.inOrder().forEach(opened::send)
            streams.positions().forEach { (id, lastSeq) -> resume(id, lastSeq) }
            watch(opened)
        } catch (expected: IOException) {
            // Link lost (closed, reset, or silent for too long): the loop reconnects.
        } finally {
            link = null
            opened.close()
            if (!closed) status.value = ConnectionStatus.Reconnecting
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
                if (!resumed && sessionId != null) streams.loseAll()
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

    private suspend fun watch(link: Link) =
        coroutineScope {
            val heartbeat =
                launch {
                    while (isActive) {
                        delay(options.heartbeatMs / 2)
                        if (System.currentTimeMillis() - link.lastSent >= options.heartbeatMs) {
                            link.send(Frame(FrameKind.Ping, 0))
                        }
                    }
                }
            try {
                // Fails with SocketTimeoutException after deadAfterMs of silence: the link is dead.
                runInterruptible { while (true) dispatch(link, link.read()) }
            } finally {
                heartbeat.cancel()
            }
        }

    private fun dispatch(link: Link, frame: Frame) {
        when (frame.kind) {
            FrameKind.Response, FrameKind.Error -> pending.complete(frame)
            FrameKind.Event -> streams.onEvent(frame.id, frame.payload)
            FrameKind.End -> streams.onEnd(frame.id, frame.payload)
            FrameKind.Ping -> link.send(Frame(FrameKind.Pong, 0))
            else -> Unit // Pong: receiving it was the point
        }
    }
}
