package org.example.kotlineditor.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.rpc.ClientOptions
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import org.example.kotlineditor.state.RemoteConnection
import org.example.kotlineditor.state.RemoteFactory
import kotlin.math.min

/**
 * A live remote development session: the SSH tunnel, the client plugged into it and the app services to hand to
 * the IDE. Closing it stops the tunnel; the daemon on the host keeps running for the next session.
 *
 * The daemon listens on a port chosen when it starts. When it dies (a crash, the host rebooted) the SSH link may be
 * fine while the client cannot reach anything: after [RECOVERY_DELAY_MS] of reconnection, the session runs the
 * installer again, which restarts the daemon if needed, and points the tunnel at the port it listens on now.
 */
class RemoteSession(
    val target: SshTarget,
    val host: RemoteHost,
    val server: RemoteServerInfo,
    val tunnel: RemoteTunnel,
    val client: RpcClient,
    private val installer: RemoteInstaller,
) : RemoteConnection {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // The daemon's secret: it is kept in a file on the host, and changes only if that file was removed.
    @Volatile private var token: String = server.token

    val remote: RemoteTarget get() = host.target

    override val services: AppServices by lazy { client.appServices() }

    override val label: String get() = target.destination

    override val home: String get() = host.home

    override val failure: StateFlow<String?> get() = tunnel.failure

    init {
        scope.launch { recoverDaemon() }
    }

    override fun close() {
        scope.cancel()
        client.close()
        tunnel.close()
    }

    private suspend fun recoverDaemon() {
        client.status.collectLatest { status ->
            // Disconnected: the daemon refused the token (it came back with a new one before the session saw it).
            if (status != ConnectionStatus.Connected) keepDaemonReachable()
        }
    }

    // Runs until the client is connected again (collectLatest then cancels it).
    private suspend fun keepDaemonReachable() {
        // A dropped SSH link comes back by itself: only look at the daemon once that had a chance.
        delay(RECOVERY_DELAY_MS)
        var backoff = RECOVERY_DELAY_MS
        while (true) {
            if (tunnel.state.value != TunnelState.Failed) checkDaemon()
            delay(backoff)
            backoff = min(backoff * 2, MAX_RECOVERY_BACKOFF_MS)
        }
    }

    @Suppress("TooGenericExceptionCaught") // any failure of a recovery attempt is retried
    private suspend fun checkDaemon() {
        try {
            val running = installer.ensureServer(host.target)
            // The token first: the client must not reach the new daemon with the old secret.
            if (running.token != token) {
                token = running.token
                client.reconnectWith(running.token)
            }
            tunnel.retarget(running.port)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Unreachable for now (the network is down too): the next attempt will tell.
        }
    }

    companion object {
        /** How long the client reconnects before the session checks that the daemon is still running. */
        const val RECOVERY_DELAY_MS = 1_000L
        private const val MAX_RECOVERY_BACKOFF_MS = 10_000L

        /**
         * Installs the server if needed, opens the tunnel and waits for the protocol handshake.
         *
         * Runs blocking SSH commands (and so must be called from an IO dispatcher); [onProgress] is called with
         * short, user-facing steps.
         */
        @Suppress("TooGenericExceptionCaught", "ThrowsCount") // wraps every transport failure into a user message
        suspend fun connect(
            target: SshTarget,
            onProgress: (String) -> Unit = {},
            options: ClientOptions = ClientOptions(heartbeatMs = 5_000, deadAfterMs = 15_000, maxBackoffMs = 2_000),
        ): RemoteSession {
            val ssh = SshClient(target)
            val installer = RemoteInstaller(ssh)
            onProgress("Checking ${target.destination}")
            val host = installer.detectHost()
            onProgress("Preparing the server on ${host.target.id}")
            val server =
                try {
                    installer.ensureServer(host.target, onProgress)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (e: Exception) {
                    throw RemoteException("Cannot prepare the server on ${target.destination}: ${e.message}", e)
                }
            currentCoroutineContext().ensureActive()
            val tunnel = RemoteTunnel(ssh, server.port)
            try {
                onProgress("Opening the SSH tunnel")
                tunnel.start()
                tunnel.awaitReady()
                onProgress("Connecting")
                val client = RpcClient("127.0.0.1", tunnel.localPort, server.token, options)
                try {
                    client.awaitConnected()
                    currentCoroutineContext().ensureActive()
                    onProgress("Connected")
                    return RemoteSession(target, host, server, tunnel, client, installer)
                } catch (e: Exception) {
                    client.close()
                    throw e
                }
            } catch (cancelled: CancellationException) {
                tunnel.close()
                throw cancelled
            } catch (e: Exception) {
                tunnel.close()
                throw if (e is RemoteException) {
                    e
                } else {
                    RemoteException("Cannot connect to ${target.destination}: ${e.message}", e)
                }
            }
        }
    }
}

/** OpenSSH implementation used by the desktop application and the end-to-end tests. */
object JvmRemoteFactory : RemoteFactory {
    override suspend fun connect(target: SshTarget, onProgress: (String) -> Unit): RemoteConnection =
        RemoteSession.connect(target, onProgress)

    override suspend fun sshHosts(): List<SshConfigHost> = SshConfig.load()
}
