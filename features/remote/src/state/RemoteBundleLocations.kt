package org.example.kotlineditor.state

import org.example.kotlineditor.remote.RemoteBundleDownload
import org.example.kotlineditor.remote.RemoteBundles

/** Where the server of remote development is read from, and where it ends up: what Settings tells the user. */
object RemoteBundleLocations {
    /** The folder of the host the server is installed in (one sub-folder per build); `~` is the SSH user's home. */
    const val HOST_DIRECTORY = "~/.kotlin-editor/server"

    /** What is said of the machine the IDE runs on: where the bundle of [source] comes from. */
    fun origin(source: RemoteBundleSource): String =
        when (source) {
            RemoteBundleSource.Local -> {
                val folders = RemoteBundles.searchedDirectories().joinToString(", ")
                "Searched in: $folders (or -Dkotlin.editor.remote.bundle)"
            }

            RemoteBundleSource.GitHub -> {
                val version = RemoteBundleDownload.ideVersion() ?: "unknown, a development run"
                "Downloaded to: ${RemoteBundleDownload.downloadDirectory()} (version: $version)"
            }
        }

    /** What is said of the host: where the bundle is unpacked, whatever the source. */
    fun destination(): String = "Installed on the host in: $HOST_DIRECTORY/<version>-<os>-<arch>-<build>/"
}
