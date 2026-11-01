package org.example.kotlineditor.remote

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.DirectoryPicker
import org.example.kotlineditor.rpc.ClientOptions
import org.example.kotlineditor.rpc.RpcClient
import org.example.kotlineditor.rpc.appServices
import org.example.kotlineditor.state.RemoteConnection
import org.example.kotlineditor.state.RemoteFactory

/**
 * A live remote development session: the SSH tunnel, the client plugged into it and the app services to hand to
 * the IDE. Closing it stops the tunnel; the daemon on the host keeps running for the next session.
 */
class RemoteSession(
    val target: SshTarget,
    val host: RemoteHost,
    val server: RemoteServerInfo,
    val tunnel: RemoteTunnel,
    val client: RpcClient,
    /** Menus and pickers that ask for a path, answered by the server. */
    val directoryPicker: DirectoryPicker,
) : RemoteConnection {
    val remote: RemoteTarget get() = host.target

    override val services: AppServices by lazy { client.appServices(directoryPicker) }

    override val label: String get() = target.destination

    override val home: String get() = host.home

    override val failure get() = tunnel.failure

    override fun close() {
        client.close()
        tunnel.close()
    }

    companion object {
        /**
         * Installs the server if needed, opens the tunnel and waits for the protocol handshake.
         *
         * Runs blocking SSH commands (and so must be called from an IO dispatcher); [onProgress] is called with
         * short, user-facing steps.
         */
        @Suppress("TooGenericExceptionCaught", "ThrowsCount") // wraps every transport failure into a user message
        suspend fun connect(
            target: SshTarget,
            directoryPicker: DirectoryPicker,
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
                    return RemoteSession(target, host, server, tunnel, client, directoryPicker)
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
        RemoteSession.connect(target, ServerPathPicker(target.destination), onProgress)

    override suspend fun sshHosts(): List<SshConfigHost> = SshConfig.load()
}
