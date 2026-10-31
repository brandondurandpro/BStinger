package org.example.kotlineditor.remote

import java.io.File
import java.io.IOException

/** A failure of the remote flow worth showing to the user as is. */
class RemoteException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Where the IDE finds the self-contained server archive for a remote target.
 *
 * A bundle is a `.tar.gz` whose root holds `VERSION`, `serverApp.jar`, `runtime/` (a jlink image) and `start.sh`.
 * Release builds put one per target next to the application, in `remote/`; developers can point
 * `-Dkotlin.editor.remote.bundle=/path/to/server-linux-x86_64.tar.gz` at a local build.
 */
object RemoteBundles {
    fun find(target: RemoteTarget): File {
        val name = "server-${target.id}.tar.gz"
        val base = applicationDirectory()
        val candidates =
            buildList {
                System.getProperty("kotlin.editor.remote.bundle")?.let { add(File(it)) }
                System.getenv("KOTLIN_EDITOR_REMOTE_BUNDLE")?.let { add(File(it)) }
                add(File(base, "remote/$name"))
                add(File(System.getProperty("user.dir"), "build/remote/$name"))
                add(File(System.getProperty("user.home"), ".kotlin-editor/bundles/$name"))
            }
        return candidates.firstOrNull { it.isFile }
            ?: throw RemoteException(
                "No server bundle for ${target.id}. Build one with tools/remote-bundle.sh, then put it in " +
                    "${File(base, "remote/$name")} or set -Dkotlin.editor.remote.bundle. Tried:\n" +
                    candidates.joinToString("\n") { "  $it" },
            )
    }

    private fun applicationDirectory(): File =
        runCatching {
            val location =
                RemoteBundles::class.java.protectionDomain.codeSource.location
                    .toURI()
            File(location).let { if (it.isDirectory) it else it.parentFile }
        }.getOrNull() ?: File(System.getProperty("user.dir"))
}
