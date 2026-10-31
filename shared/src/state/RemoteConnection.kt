package org.example.kotlineditor.state

import kotlinx.coroutines.flow.StateFlow
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.remote.SshTarget

/**
 * A remote development session, as the common UI sees it. The SSH transport is JVM-specific and implements this
 * behind [RemoteFactory], so the welcome screen and [AppState] never touch processes or sockets.
 */
interface RemoteConnection {
    /** Everything the IDE uses on the remote server. */
    val services: AppServices

    /** `user@host`, shown in the status bar. */
    val label: String

    /** Home directory of the SSH user on the host; folder browsing starts there. */
    val home: String

    /** Why the SSH tunnel cannot be established any more, or null while it is healthy. */
    val failure: StateFlow<String?>

    /** Stops the tunnel; the server daemon keeps running for the next session. */
    fun close()
}

/** Creates remote sessions; the desktop application plugs in the OpenSSH implementation. */
interface RemoteFactory {
    /**
     * Installs the server on [target] if needed, starts its daemon, opens the tunnel and waits for the protocol
     * handshake. Blocking work happens on the caller's IO dispatcher.
     */
    suspend fun connect(target: SshTarget, onProgress: (String) -> Unit = {}): RemoteConnection
}
