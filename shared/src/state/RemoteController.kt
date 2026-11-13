package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.remote.SshConfigHost
import org.example.kotlineditor.remote.SshTarget
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame

/**
 * The "Remote Development" dialog and what it lists: the SSH hosts, their tags, the remote folders already opened. It
 * establishes the connection, then hands it to [onConnected], which shows that server; it never holds a connection.
 */
@Stable
@Suppress("TooManyFunctions")
class RemoteController(
    preferences: PreferenceHub,
    private val scope: CoroutineScope,
    /** Creates SSH sessions; null in previews and tests that do not exercise remote development. */
    private val remoteFactory: RemoteFactory?,
    /** Shows the server of a connection just established; it owns the connection from then on. */
    private val onConnected: suspend (RemoteConnection, SshTarget) -> Unit,
    /** Where the server to install on a host comes from, read at each connection (a Settings choice). */
    private val bundleSource: () -> RemoteBundleSource = { RemoteBundleSource.Local },
) : RemoteHost {
    /** The "Remote Development" dialog, when open. */
    var dialog: RemoteDialogState? by mutableStateOf(null)
        private set

    /** Hosts of the client's `~/.ssh/config`, shown in the remote dialog. */
    override var sshHosts: List<SshConfigHost> by mutableStateOf(emptyList())
        private set

    /** Whether `~/.ssh/config` is being read (it resolves each alias with `ssh -G`). */
    override var sshHostsLoading: Boolean by mutableStateOf(false)
        private set

    /** Remote folders already opened, newest first, searchable from the remote dialog. */
    override val remoteWorkspaces = mutableStateListOf<RemoteWorkspace>()

    private val hostTags = mutableStateMapOf<String, List<String>>()

    /** What the remote connection is doing, while it is being established. */
    override var remoteStatus: String? by mutableStateOf(null)
        private set

    // The last connection established: the dialog starts from it, and the folders opened there are recorded with it.
    private var lastRemote: SshTarget? by mutableStateOf(RemotePreferenceKeys.read(preferences.known))
    private var job: Job? = null

    init {
        remoteWorkspaces.addAll(RemoteWorkspaceCodec.decode(preferences[RemotePreferenceKeys.WORKSPACES].orEmpty()))
        hostTags.putAll(HostTagsCodec.decode(preferences[RemotePreferenceKeys.TAGS].orEmpty()))
    }

    /** Opens the "Remote Development" dialog, prefilled with the last connection and the SSH catalog. */
    fun openDialog() {
        val previousTarget = lastRemote
        val prefilled = RemoteDialogState.from(previousTarget)
        dialog =
            if (previousTarget == null) {
                prefilled
            } else {
                prefilled.copy(tags = tagsFor(previousTarget.tagKey).joinToString(", "))
            }
        loadSshHosts()
    }

    private fun loadSshHosts() {
        val factory = remoteFactory
        if (factory == null) {
            sshHosts = emptyList()
            return
        }
        sshHostsLoading = true
        scope.launch {
            sshHosts = withContext(Dispatchers.IO) { runCatching { factory.sshHosts() }.getOrDefault(emptyList()) }
            sshHostsLoading = false
        }
    }

    /** A host of `~/.ssh/config` was picked: use the alias, and let ssh resolve user, port and key. */
    override fun selectSshHost(host: SshConfigHost) {
        val current = dialog ?: return
        dialog =
            current.copy(
                alias = host.alias,
                host = host.alias,
                user = host.user.orEmpty(),
                port = host.port.toString(),
                identityFile = "",
                projectPath = "",
                tags = tagsFor(host.alias).joinToString(", "),
                manual = false,
                selectedRow = "h:${host.alias}",
                error = null,
            )
    }

    /** A saved remote folder was picked: fill the connection and its path, ready to connect. */
    override fun selectRemoteWorkspace(workspace: RemoteWorkspace) {
        val current = dialog ?: return
        dialog =
            current.copy(
                alias = workspace.target.alias.orEmpty(),
                host = workspace.target.host,
                user = workspace.target.user.orEmpty(),
                port = workspace.target.port.toString(),
                identityFile = workspace.target.identityFile.orEmpty(),
                projectPath = workspace.path,
                tags = tagsFor(workspace.target.tagKey).joinToString(", "),
                manual = false,
                selectedRow = "w:${workspace.key}",
                error = null,
            )
    }

    /** Tags of a host, by alias or by host:port:user. */
    override fun tagsFor(key: String): List<String> = hostTags[key].orEmpty()

    /** The tags field changed: kept in the dialog, and saved right away for a `~/.ssh/config` alias. */
    override fun editRemoteTags(text: String) {
        val current = dialog ?: return
        dialog = current.copy(tags = text)
        val alias = current.alias.trim()
        if (alias.isNotEmpty()) saveTags(alias, text)
    }

    /** Shows the manual form to add a host that is not in `~/.ssh/config`. */
    override fun addRemoteHost() {
        val current = dialog ?: return
        dialog =
            current.copy(
                manual = true,
                host = "",
                user = "",
                port = "22",
                identityFile = "",
                projectPath = "",
                alias = "",
                tags = "",
                selectedRow = "",
                error = null,
            )
    }

    /** Back to the lists; whatever was typed stays selected so it can still be connected. */
    override fun chooseRemoteHost() {
        val current = dialog ?: return
        dialog = current.copy(manual = false, error = null)
    }

    private fun saveTags(key: String, text: String) {
        val tags = parseTags(text)
        if (tags.isEmpty()) hostTags.remove(key) else hostTags[key] = tags
    }

    /** Updates the dialog while the user types. */
    override fun updateRemoteDialog(dialog: RemoteDialogState) {
        this.dialog = dialog
    }

    /** Closes the dialog and cancels a connection in progress. */
    override fun dismissRemoteDialog() {
        job?.cancel()
        job = null
        dialog = null
        remoteStatus = null
    }

    /**
     * Installs the server on [dialog]'s target and opens the SSH tunnel, then hands the connection to [onConnected].
     * Once established, the switch to that server runs to its end: cancelled halfway, the IDE would show neither.
     */
    override fun connectRemote(dialog: RemoteDialogState) {
        val factory =
            remoteFactory ?: run {
                this.dialog = dialog.copy(error = "Remote development is not available in this build")
                return
            }
        val target =
            dialog.toSshTarget().getOrElse {
                this.dialog = dialog.copy(error = it.message)
                return
            }
        // A manual host's tags are saved when the target is confirmed; alias tags are saved as they are typed.
        saveTags(target.tagKey, dialog.tags)
        job?.cancel()
        this.dialog = dialog.copy(connecting = true, error = null)
        remoteStatus = "Checking ${target.destination}"
        job =
            scope.launch {
                val connection = establish(factory, target, dialog)
                if (connection != null) withContext(NonCancellable) { onConnected(connection, target) }
            }
    }

    // The connection, or null when it failed (the failure is shown in the dialog).
    @Suppress("TooGenericExceptionCaught") // every failure of the SSH flow belongs in the dialog
    private suspend fun establish(
        factory: RemoteFactory,
        target: SshTarget,
        dialog: RemoteDialogState,
    ): RemoteConnection? {
        // Set as soon as the connection exists: withContext drops its result when it is cancelled right as it
        // returns, and the tunnel must not outlive a cancelled connection.
        var connected: RemoteConnection? = null
        try {
            ClientTelemetry.measureToFrame(Operation.RemoteConnect) {
                withContext(Dispatchers.IO) {
                    connected =
                        factory.connect(target, dialog.replaceServer, bundleSource()) { step ->
                            scope.launch { remoteStatus = step }
                        }
                }
            }
        } catch (cancelled: CancellationException) {
            connected?.close()
            throw cancelled
        } catch (e: Exception) {
            remoteStatus = null
            this.dialog = dialog.failedWith(e, target.destination)
            return null
        }
        return checkNotNull(connected)
    }

    /** The server of [target] is shown: the dialog closes, and the next one starts from this connection. */
    fun connectionShown(target: SshTarget) {
        lastRemote = target
        dialog = null
        remoteStatus = null
    }

    /** A folder was opened on the remote server: recorded first in the remote folders of the dialog. */
    fun recordWorkspace(path: String) {
        val target = lastRemote?.copy(projectPath = null) ?: return
        remoteWorkspaces.removeAll { it.matches(target, path) }
        remoteWorkspaces.add(0, RemoteWorkspace(target, path, System.currentTimeMillis()))
        while (remoteWorkspaces.size > RemoteWorkspaceCodec.MAX) {
            remoteWorkspaces.removeAt(remoteWorkspaces.lastIndex)
        }
    }

    /** The preferences the remote development keeps: the last connection, the remote folders and the tags. */
    fun toPreferences(): Map<String, String> {
        val last = lastRemote?.let(RemotePreferenceKeys::write).orEmpty()
        val workspaces = RemotePreferenceKeys.WORKSPACES to RemoteWorkspaceCodec.encode(remoteWorkspaces)
        val tags = RemotePreferenceKeys.TAGS to HostTagsCodec.encode(hostTags)
        return last + workspaces + tags
    }
}
