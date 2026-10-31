package org.example.kotlineditor.server

import org.example.kotlineditor.rpc.Protocol
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.util.Properties

/**
 * The file a daemonized server writes once it listens, so its client finds its port and token without passing
 * them on the command line (where every user of the machine would see them).
 *
 * It is a Java properties file, world-unreadable when the file system supports permissions:
 * ```
 * version=2026.1
 * protocol=5
 * host=127.0.0.1
 * port=42173
 * pid=8842
 * token=...
 * ```
 */
object ServerState {
    /** Writes the state of [server] to [file], atomically and readable by its owner only. */
    fun write(
        file: File,
        server: EditorServer,
        host: String,
        token: String,
        startedAt: Long = System.currentTimeMillis(),
    ) {
        val properties =
            Properties().apply {
                setProperty("version", Protocol.APP_VERSION)
                setProperty("protocol", Protocol.VERSION.toString())
                setProperty("host", host)
                setProperty("port", server.port.toString())
                setProperty("pid", ProcessHandle.current().pid().toString())
                setProperty("token", token)
                setProperty("startedAt", startedAt.toString())
            }
        val directory = file.absoluteFile.parentFile
        directory.mkdirs()
        val temporary = File(directory, "${file.name}.tmp")
        temporary.outputStream().use { properties.store(it, "Kotlin Editor server") }
        restrictToOwner(temporary)
        moveReplacing(temporary, file)
        restrictToOwner(file)
    }

    /** Removes [file] if it still belongs to the current process (a later server must keep its own state file). */
    fun removeIfOwned(file: File) {
        val properties = read(file) ?: return
        if (properties.getProperty("pid") == ProcessHandle.current().pid().toString()) file.delete()
    }

    /** The state in [file], or null when it is missing or unreadable. */
    fun read(file: File): Properties? =
        runCatching {
            file.inputStream().use { input -> Properties().apply { load(input) } }
        }.getOrNull()

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: Exception) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun restrictToOwner(file: File) {
        // Windows has no POSIX permissions; its ACLs already keep the user's home private.
        runCatching {
            Files.setPosixFilePermissions(
                file.toPath(),
                PosixFilePermissions.fromString("rw-------"),
            )
        }.onFailure { if (it !is UnsupportedOperationException) throw it }
    }

    /** Permissions of [file], for tests: `rw-------` on POSIX file systems, null elsewhere. */
    fun permissions(file: File): Set<PosixFilePermission>? =
        runCatching { Files.getPosixFilePermissions(file.toPath()) }.getOrNull()
}
