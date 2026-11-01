package org.example.kotlineditor.remote

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

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
    private const val BUFFER_SIZE = 64 * 1024
    private const val BUILD_ID_LENGTH = 16

    private val buildIds = ConcurrentHashMap<Triple<String, Long, Long>, String>()

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

    /**
     * Identity of [bundle]: the start of its SHA-256. The remote side installs and runs a bundle by this id, so a
     * rebuilt server is installed even when [org.example.kotlineditor.rpc.Protocol.APP_VERSION] did not change.
     * Hashed once per file version, as connecting and reconnecting ask for it.
     */
    fun buildId(bundle: File): String {
        val key = Triple(bundle.absolutePath, bundle.length(), bundle.lastModified())
        return buildIds.getOrPut(key) {
            val digest = MessageDigest.getInstance("SHA-256")
            bundle.inputStream().use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }.take(BUILD_ID_LENGTH)
        }
    }

    private fun applicationDirectory(): File =
        runCatching {
            val location =
                RemoteBundles::class.java.protectionDomain.codeSource.location
                    .toURI()
            File(location).let { if (it.isDirectory) it else it.parentFile }
        }.getOrNull() ?: File(System.getProperty("user.dir"))
}
