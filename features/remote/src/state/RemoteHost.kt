package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.remote.SshConfigHost

/** What the "Remote Development" dialog lists: SSH hosts and remote folders already opened, with their tags. */
interface RemoteCatalogSource {
    /** Hosts of the client's `~/.ssh/config`. */
    val sshHosts: List<SshConfigHost>

    /** True while [sshHosts] is being read. */
    val sshHostsLoading: Boolean

    /** Remote folders already opened, newest first. */
    val remoteWorkspaces: List<RemoteWorkspace>

    /** Tags of the host or workspace [key]. */
    fun tagsFor(key: String): List<String>
}

/** The application as seen by the "Remote Development" dialog; `AppState` implements it in `shared`. */
interface RemoteHost : RemoteCatalogSource {
    /** What the remote connection is doing, while it is being established. */
    val remoteStatus: String?

    fun updateRemoteDialog(dialog: RemoteDialogState)

    fun selectSshHost(host: SshConfigHost)

    fun selectRemoteWorkspace(workspace: RemoteWorkspace)

    fun editRemoteTags(text: String)

    fun addRemoteHost()

    fun chooseRemoteHost()

    fun dismissRemoteDialog()

    fun connectRemote(dialog: RemoteDialogState)
}

/** The application as seen by the "Open Project" dialog; `AppState` implements it in `shared`. */
interface ProjectPickerHost {
    /** `user@host` of the remote session, or null when the server is local. */
    val remoteLabel: String?

    /** Directory the dialog starts in. */
    fun projectPickerStart(): String

    /** Home directory of the server, or null when unknown. */
    fun projectPickerHome(): String?

    fun rememberProjectPickerDirectory(path: String)

    suspend fun resolvePickerDirectory(path: String): FileEntry?

    suspend fun pickerSubdirectories(directory: FileEntry): List<FileEntry>

    fun openFromProjectPicker(path: String)

    fun closeProjectPicker()
}
