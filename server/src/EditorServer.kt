package org.example.kotlineditor.server

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.Frame
import org.example.kotlineditor.rpc.FrameKind
import org.example.kotlineditor.rpc.InvalidMessageException
import org.example.kotlineditor.rpc.Protocol
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.params
import org.example.kotlineditor.rpc.rpcError
import org.example.kotlineditor.rpc.stringOrNull
import java.io.Closeable
import java.io.IOException
import java.net.BindException
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
    /**
     * How long [EditorServer.start] waits for a port the previous listener has just released: a crash followed by
     * an automatic restart can find it briefly in use, and the server should come back on the same port.
     */
    val bindRetryMs: Long = 2_000,
)

/**
 * The Kotlin Editor server: all file system, search, Git and shell logic runs here; clients only display.
 * One thread per connection reads messages; requests run concurrently on coroutines and answer in any order.
 */
class EditorServer(
    private val services: LocalServices = LocalServices(),
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
        val socket = ServerSocket()
        socket.reuseAddress = true
        bindWithRetry(socket, InetSocketAddress(host, port))
        serverSocket = socket
        thread(name = "editor-server-accept", isDaemon = true) { acceptLoop() }
        scope.launch { sweepSessions() }
        return this
    }

    // A listener that was just closed can leave its port unavailable for a moment; wait instead of failing the
    // restart. A port held by another program still fails, after the retry window.
    private fun bindWithRetry(socket: ServerSocket, address: InetSocketAddress) {
        val deadline = System.nanoTime() + options.bindRetryMs * 1_000_000
        while (true) {
            try {
                socket.bind(address, BACKLOG)
                return
            } catch (inUse: BindException) {
                if (System.nanoTime() >= deadline) {
                    runCatching { socket.close() }
                    throw inUse
                }
                Thread.sleep(BIND_RETRY_DELAY_MS)
            }
        }
    }

    override fun close() {
        closed = true
        runCatching { serverSocket?.close() }
        sessions.values.forEach { it.dispose() }
        sessions.clear()
        scope.cancel()
        services.close()
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
            while (session != null && !closed) {
                val frame =
                    try {
                        connection.read()
                    } catch (invalid: InvalidMessageException) {
                        val error = rpcError(invalid.code, invalid.message.orEmpty())
                        connection.send(Frame(FrameKind.Error, invalid.id, error))
                        continue
                    }
                session.onFrame(connection, frame)
            }
        } catch (expected: IOException) {
            // Connection lost or idle: the session survives for a grace period.
        } finally {
            session?.detach(connection)
            connection.close()
        }
    }

    private fun handshake(connection: ServerConnection): ServerSession? {
        val hello = connection.read()
        val isHello = hello.kind == FrameKind.Hello
        // Malformed params are rejected like a missing hello, never thrown out of the connection thread.
        val params = if (isHello) runCatching { hello.payload.params() }.getOrNull() else null
        val version = params?.let { runCatching { it.int("version") }.getOrNull() } ?: -1
        val presented = params?.let { runCatching { it.stringOrNull("token") }.getOrNull() }.orEmpty()
        val previous = params?.let { runCatching { it.stringOrNull("session") }.getOrNull() }
        val reason =
            when {
                params == null -> "Expected a connection/hello request with named params"
                version != Protocol.VERSION -> "Unsupported protocol version $version (expected ${Protocol.VERSION})"
                !MessageDigest.isEqual(presented.encodeToByteArray(), token.encodeToByteArray()) -> "Invalid token"
                else -> null
            }
        reason?.let { connection.send(Frame(FrameKind.Reject, Protocol.HELLO_ID, rpcError(Protocol.REJECTED, it))) }
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
                onDispose = { id -> if (services.languageServersStarted) services.languageServers.release(id) },
            ).also { sessions[it.id] = it }
        session.attach(connection)
        val welcome =
            buildJsonObject {
                put("session", session.id)
                put("resumed", existing != null)
            }
        connection.send(Frame(FrameKind.Welcome, Protocol.HELLO_ID, welcome))
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
        private const val BIND_RETRY_DELAY_MS = 20L
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
