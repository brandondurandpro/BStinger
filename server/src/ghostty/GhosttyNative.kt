package org.example.kotlineditor.server.ghostty

import org.example.kotlineditor.server.ghosttyPlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

/**
 * Loads the libghostty-vt native library shipped by GhosttyFX.
 *
 * The bindings and the native library are published per platform: the artifact on the classpath carries a
 * `native/<platform>.zip` resource whose single entry is the shared library. libghostty-vt exposes no stable C
 * ABI yet, so the artifact version and this loader are pinned together in `libs.versions.toml`.
 *
 * The resource comes with the bindings in use: the Linux dependency, or another platform's artifact that
 * `TerminalEngines` loaded in its own class loader.
 */
internal object GhosttyNative {
    private val loaded = AtomicBoolean()

    /** Makes the native library available to the bindings; safe to call from several threads. */
    fun ensureLoaded() {
        if (loaded.get()) return
        synchronized(this) {
            if (loaded.get()) return
            load()
            loaded.set(true)
        }
    }

    private fun load() {
        val platform = ghosttyPlatform()
        val resource = "/native/$platform.zip"
        val archive =
            ZipInputStream(
                GhosttyNative::class.java.getResourceAsStream(resource)
                    ?: error(
                        "No libghostty-vt for $platform: add ghosttyfx-$platform.jar " +
                            "to server/resources/ghostty (see TerminalEngines)",
                    ),
            )
        val directory = Files.createTempDirectory("ghostty-vt")
        val library = extract(archive, directory)
        directory.toFile().deleteOnExit()
        library.toFile().deleteOnExit()
        System.load(library.toAbsolutePath().toString())
    }

    private fun extract(archive: ZipInputStream, directory: Path): Path {
        archive.use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: error("The libghostty-vt archive is empty")
                if (entry.isDirectory) continue
                val target = directory.resolve(entry.name.substringAfterLast('/'))
                Files.newOutputStream(target).use { zip.copyTo(it) }
                return target
            }
        }
    }
}
