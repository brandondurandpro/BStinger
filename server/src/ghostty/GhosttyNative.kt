package org.example.kotlineditor.server.ghostty

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
 * Only the Linux artifact is a dependency: the bindings of each platform have the same classes and cannot share
 * the classpath. Its bindings also fit Windows, whose 64-bit C types are the same size, so the Windows archive of the
 * same version is copied from `ghosttyfx-windows-x86_64` into `server/resources/native`.
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
        val platform = platformId()
        val resource = "/native/$platform.zip"
        val archive =
            ZipInputStream(
                GhosttyNative::class.java.getResourceAsStream(resource)
                    ?: error(
                        "No libghostty-vt for $platform: add io.github.vlaaad:ghosttyfx-$platform " +
                            "to the server module (see docs/TERMINAL.md)",
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

    private fun platformId(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        val name =
            when {
                os.startsWith("linux") -> "linux"
                os.startsWith("mac") || os.startsWith("darwin") -> "macos"
                os.startsWith("windows") -> "windows"
                else -> error("Unsupported operating system for libghostty-vt: $os")
            }
        val cpu =
            when (arch) {
                "x86_64", "amd64" -> "x86_64"
                "aarch64", "arm64" -> "aarch64"
                else -> error("Unsupported architecture for libghostty-vt: $arch")
            }
        return "$name-$cpu"
    }
}
