package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import java.io.IOException

// The machines of the server's SSH configuration; the form works without them (a host can be typed).
internal fun DeploymentsState.loadSshHosts() {
    project.scope.launch {
        try {
            sshHosts = project.services.deployer.sshHosts()
        } catch (_: IOException) {
            sshHosts = emptyList()
        }
    }
}

/** Switches the form between a folder of the disk and a machine reached over SSH; the destination typed is dropped. */
fun DeploymentsState.setRemote(remote: Boolean) {
    val current = draft ?: return
    if (current.remote == remote) return
    draft = current.copy(remote = remote, destination = "", error = null)
}

/** The machine chosen for an SSH destination (an alias of the configuration, or `user@host`); the folder is kept. */
fun DeploymentsState.setSshHost(host: String) {
    val current = draft ?: return
    val folder = sshFolderOf(current.destination)
    val destination = if (host.isBlank()) "" else "${host.trim()}:$folder"
    draft = current.copy(destination = destination, error = null)
}

/** The folder of an SSH destination, on its machine; empty is the home of the SSH user. */
fun DeploymentsState.setSshFolder(folder: String) {
    val current = draft ?: return
    val host = sshHostOf(current.destination)
    if (host.isEmpty()) return
    draft = current.copy(destination = "$host:$folder", error = null)
}

/** The machine of an SSH [destination] as typed (`host:folder`), empty when none. */
fun sshHostOf(destination: String): String = destination.substringBefore(':', "")

/** The folder of an SSH [destination] as typed (`host:folder`), empty when none. */
fun sshFolderOf(destination: String): String = destination.substringAfter(':', "")
