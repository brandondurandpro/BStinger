package org.example.kotlineditor.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import java.io.Closeable
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.math.min

/** State of the SSH link, from the UI's point of view. */
enum class TunnelState { Starting, Connected, Reconnecting, Failed }

/**
 * A local port forwarded to the server's loopback port, kept alive for the whole session.
 *
 * `ssh -L` processes die when the network, the laptop or the server goes away. The supervisor starts it again on
 * the same local port: the [RpcClient] then reconnects to the daemon, which never stopped, and resumes its
 * sessions and streams. Authentication and host-key failures are not retried.
 */
class RemoteTunnel(private val ssh: SshClient, remotePort: Int) : Closeable {
    /** Loopback port of the daemon on the host; it changes when the daemon restarts (it listens on port 0). */
    @Volatile var remotePort: Int = remotePort
        private set

    /** Local port the client connects to. */
    val localPort: Int = freePort()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val firstConnection = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(TunnelState.Starting)
    private val mutableFailure = MutableStateFlow<String?>(null)

    /** Whether the tunnel is up, going down or cannot be established. */
    val state: StateFlow<TunnelState> = mutableState.asStateFlow()

    /** Why SSH refuses the connection (bad key, changed host key…), or null while retrying would help. */
    val failure: StateFlow<String?> = mutableFailure.asStateFlow()

    @Volatile private var current: ForwardProcess? = null

    @Volatile private var closed = false

    private var supervisor: Job? = null

    /** Starts supervising the tunnel; [awaitReady] waits for the first successful connection. */
    fun start() {
        if (supervisor != null) return
        supervisor = scope.launch { supervise() }
    }

    /** Suspends until the tunnel forwards to the server, failing after [timeoutMs]. */
    suspend fun awaitReady(timeoutMs: Long = 30_000) {
        withTimeout(timeoutMs) { firstConnection.await() }
    }

    /**
     * Points the tunnel at the daemon's new port: the running ssh is stopped and the supervisor starts the next
     * one on the same local port, so the [RpcClient] reconnects without knowing anything changed.
     */
    fun retarget(port: Int) {
        if (port == remotePort || closed) return
        remotePort = port
        current?.close()
    }

    override fun close() {
        closed = true
        current?.close()
        current = null
        scope.cancel()
    }

    @Suppress("CognitiveComplexMethod", "ReturnCount") // a supervisor loop: every exit is a state of the link
    private suspend fun supervise() {
        var backoff = FIRST_BACKOFF_MS
        while (!closed) {
            val target = remotePort
            val process =
                try {
                    ssh.forward(localPort, target)
                } catch (e: IOException) {
                    fail(RemoteException("Cannot start the SSH tunnel: ${e.message}", e))
                    return
                }
            current = process
            val ready = waitForForward(process)
            if (closed) {
                process.close()
                return
            }
            if (ready) {
                mutableState.value = TunnelState.Connected
                mutableFailure.value = null
                if (!firstConnection.isCompleted) firstConnection.complete(Unit)
                backoff = FIRST_BACKOFF_MS
            } else {
                mutableState.value = TunnelState.Reconnecting
            }
            while (!closed && process.isAlive) delay(PROCESS_POLL_MS)
            process.awaitErrors()
            process.close()
            current = null
            if (closed) return
            if (target != remotePort) {
                // Retargeted: start the new forward at once.
                backoff = FIRST_BACKOFF_MS
                continue
            }
            if (process.isFatal()) {
                fail(RemoteException("SSH refused the connection: ${process.errors}"))
                return
            }
            mutableState.value = TunnelState.Reconnecting
            delay(backoff)
            backoff = min(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    @Suppress("ReturnCount") // two ways for a probe to fail, one for it to succeed
    private suspend fun waitForForward(process: ForwardProcess): Boolean {
        val deadline = System.currentTimeMillis() + CONNECT_TIMEOUT_MS
        while (!closed && System.currentTimeMillis() < deadline) {
            if (!process.isAlive) return false
            if (probe()) return true
            delay(PROBE_INTERVAL_MS)
        }
        return false
    }

    // ssh accepts the local connection before it knows whether the remote port is open; the RpcClient's own
    // reconnection covers that case, this only avoids waiting for a tunnel that never comes up.
    private fun probe(): Boolean =
        try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", localPort), PROBE_TIMEOUT_MS) }
            true
        } catch (_: IOException) {
            false
        }

    private fun fail(error: RemoteException) {
        mutableState.value = TunnelState.Failed
        mutableFailure.value = error.message
        if (!firstConnection.isCompleted) firstConnection.completeExceptionally(error)
    }

    private companion object {
        const val FIRST_BACKOFF_MS = 100L
        const val MAX_BACKOFF_MS = 3_000L
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val PROBE_INTERVAL_MS = 100L
        const val PROBE_TIMEOUT_MS = 250
        const val PROCESS_POLL_MS = 200L

        fun freePort(): Int =
            ServerSocket().use {
                it.bind(InetSocketAddress("127.0.0.1", 0))
                it.localPort
            }
    }
}
