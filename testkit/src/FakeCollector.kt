package org.example.kotlineditor.e2e

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/** One OTLP request the collector received: its path (`/v1/traces`, `/v1/metrics`) and its protobuf body. */
class CollectedRequest(val path: String, val body: ByteArray) {
    /** Whether the body holds [text] as UTF-8, as protobuf stores strings (span names, attribute values). */
    operator fun contains(text: String): Boolean = String(body, Charsets.UTF_8).contains(text)
}

/**
 * An OpenTelemetry collector on loopback receiving OTLP/HTTP, for the tests: it keeps what it receives and answers
 * 200. [blackHole] makes it accept connections and never answer (a collector behind a dead VPN); [close] makes it
 * unreachable.
 */
class FakeCollector : AutoCloseable {
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
    private val received = CopyOnWriteArrayList<CollectedRequest>()

    /** Accepts and never answers. */
    @Volatile var blackHole = false

    init {
        server.executor = Executors.newCachedThreadPool { Thread(it, "fake-collector").apply { isDaemon = true } }
        server.createContext("/") { exchange -> answer(exchange) }
        server.start()
    }

    /** The endpoint to export to (`OTEL_EXPORTER_OTLP_ENDPOINT`). */
    val endpoint: String get() = "http://127.0.0.1:${server.address.port}"

    /** What was received so far. */
    val requests: List<CollectedRequest> get() = received.toList()

    /** The trace requests received. */
    val traces: List<CollectedRequest> get() = requests.filter { it.path == "/v1/traces" }

    /** The metric requests received. */
    val metrics: List<CollectedRequest> get() = requests.filter { it.path == "/v1/metrics" }

    private fun answer(exchange: HttpExchange) {
        exchange.use {
            val body = it.requestBody.readAllBytes()
            if (blackHole) {
                // Holds the request until the client gives up (or the collector closes).
                while (blackHole) Thread.sleep(HOLD_MS)
            }
            received += CollectedRequest(it.requestURI.path, body)
            it.sendResponseHeaders(OK, -1)
        }
    }

    override fun close() {
        blackHole = false
        server.stop(0)
    }

    companion object {
        private const val OK = 200
        private const val HOLD_MS = 20L

        /**
         * An endpoint whose port accepts TCP connections and never reads nor answers them: the link went silent
         * (laptop asleep, a NAT that drops the connection quietly). Closed by closing the returned socket.
         */
        fun silentEndpoint(): Pair<String, AutoCloseable> {
            val socket = ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress())
            val accepted = CopyOnWriteArrayList<Socket>()
            thread(isDaemon = true, name = "silent-collector") {
                runCatching { while (!socket.isClosed) accepted += socket.accept() }
            }
            val closer =
                AutoCloseable {
                    socket.close()
                    accepted.forEach { runCatching { it.close() } }
                }
            return "http://127.0.0.1:${socket.localPort}" to closer
        }

        private const val BACKLOG = 50
    }
}
