package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.WireReader
import org.example.kotlineditor.rpc.WireWriter
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/** Timeouts and buffers of an [EditorServer]. */
class ServerOptions(
    /** A client that sends nothing (not even a ping) for this long is disconnected; its session stays. */
    val idleTimeoutMs: Int = 30_000,
    /** How long a disconnected session (its shells, its answers) waits for the client to come back. */
    val sessionGraceMs: Long = 120_000,
    /** Answers of mutating requests kept to deduplicate retries. */
    val responseCacheSize: Int = 1024,
    /** Shell output batches kept to replay after a reconnection. */
    val streamBacklog: Int = 4096,
)

/**
 * The Kotlin Editor server: all file system, search, Git and shell logic runs here; clients only display.
 * One thread per connection reads frames; requests run concurrently on coroutines and answer in any order.
 */
class EditorServer(
    services: LocalServices = LocalServices(),
    /** Secret a client must present in its handshake. */
    val token: String = newToken(),
    private val options: ServerOptions = ServerOptions(),
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sessions = ConcurrentHashMap<String, ServerSession>()
    private val handlers = requestHandlers(services)
    private var serverSocket: ServerSocket? = null

    @Volatile private var closed = false

    /** Port the server listens on (once started). */
    val port: Int get() = checkNotNull(serverSocket) { "Server not started" }.localPort

    /** Number of client sessions, connected or waiting for their client to come back. */
    val sessionCount: Int get() = sessions.size

    /** Binds to [host] (loopback by default: remote access must be asked for explicitly). Port 0 = any. */
    fun start(host: String = "127.0.0.1", port: Int = 0): EditorServer {
        serverSocket =
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(host, port), BACKLOG)
            }
        thread(name = "editor-server-accept", isDaemon = true) { acceptLoop() }
        scope.launch { sweepSessions() }
        return this
    }

    override fun close() {
        closed = true
        runCatching { serverSocket?.close() }
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        scope.cancel()
    }

    /** Drops every connection abruptly while keeping sessions (tests simulate network failures with it). */
    fun dropConnections() = sessions.values.forEach { it.connection?.close() }

    private fun acceptLoop() {
        val socket = checkNotNull(serverSocket)
        while (!closed) {
            runCatching { socket.accept() }.onSuccess { client ->
                thread(name = "editor-server-connection", isDaemon = true) { serve(client) }
            }
        }
    }

    private fun serve(socket: Socket) {
        socket.tcpNoDelay = true
        socket.keepAlive = true
        socket.soTimeout = options.idleTimeoutMs
        val connection = ServerConnection(socket)
        var session: ServerSession? = null
        try {
            session = handshake(connection)
            while (session != null && !closed) session.onFrame(connection, connection.read())
        } catch (expected: IOException) {
            // Connection lost or idle: the session survives for a grace period.
        } finally {
            session?.detach(connection)
            connection.close()
        }
    }

    private fun handshake(connection: ServerConnection): ServerSession? {
        val hello = connection.read()
        val reader = WireReader(hello.body)
        val isHello = hello.kind == FrameKind.Hello
        val version = if (isHello) reader.int() else -1
        val presented = if (isHello) reader.string() else ""
        val previous = if (isHello) reader.stringOrNull() else null
        val reason =
            when {
                !isHello -> "Expected a Hello frame"
                version != Protocol.VERSION -> "Unsupported protocol version $version (expected ${Protocol.VERSION})"
                !MessageDigest.isEqual(presented.encodeToByteArray(), token.encodeToByteArray()) -> "Invalid token"
                else -> null
            }
        reason?.let { connection.send(Frame(FrameKind.Reject, 0, WireWriter().string(it).toByteArray())) }
        return if (reason != null) null else join(connection, previous)
    }

    private fun join(connection: ServerConnection, previous: String?): ServerSession {
        val existing = previous?.let { sessions[it] }
        val session =
            existing ?: ServerSession(
                UUID.randomUUID().toString(),
                scope,
                handlers,
                options,
            ).also { sessions[it.id] = it }
        session.attach(connection)
        val welcome = WireWriter().string(session.id).bool(existing != null).toByteArray()
        connection.send(Frame(FrameKind.Welcome, 0, welcome))
        return session
    }

    private suspend fun sweepSessions() {
        while (scope.isActive) {
            delay((options.sessionGraceMs / 2).coerceIn(MIN_SWEEP_MS, MAX_SWEEP_MS))
            val now = System.currentTimeMillis()
            sessions.values.filter { it.connection == null && now - it.detachedAt > options.sessionGraceMs }.forEach {
                sessions.remove(it.id)
                it.dispose()
            }
        }
    }

    /** Token generation. */
    companion object {
        private const val BACKLOG = 128
        private const val TOKEN_BYTES = 24
        private const val MIN_SWEEP_MS = 50L
        private const val MAX_SWEEP_MS = 10_000L

        /** A random 192-bit token, hex encoded. */
        fun newToken(): String {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
