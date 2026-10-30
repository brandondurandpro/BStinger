package org.example.kotlineditor.server

import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalSnapshot
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Terminal emulator of a [ServerTerminal]: parses what the PTY prints and keeps the grid.
 *
 * Declared outside the `ghostty` package, which [TerminalEngines] may load in a class loader of its own.
 */
internal interface TerminalEngine : AutoCloseable {
    /** Feeds what the PTY printed. */
    fun feed(bytes: ByteArray)

    /** Encodes a key the way the running program expects it. */
    fun encodeKey(event: TerminalKeyEvent)

    /** Pastes text, with bracketed-paste markers when the program enabled them. */
    fun paste(text: String)

    /** Resizes the grid and the pixel size of a cell. */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int)

    /** Scrolls the viewport: positive goes up into the scrollback. */
    fun scroll(delta: Int)

    /** Clears the screen and the scrollback; `false` when a full-screen program must redraw itself instead. */
    fun clear(): Boolean

    /** Reads the whole visible screen. */
    fun snapshot(running: Boolean = true, exitCode: Int? = null): TerminalSnapshot
}

/** Creates [TerminalEngine]s; implemented in the `ghostty` package and found by name in its class loader. */
internal fun interface TerminalEngineFactory {
    fun create(
        columns: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
        writePty: (ByteArray) -> Unit,
    ): TerminalEngine
}

/** Id of the running platform in the GhosttyFX artifacts and resources, like `linux-x86_64`. */
internal fun ghosttyPlatform(): String {
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

/**
 * Picks the libghostty-vt bindings of the running platform.
 *
 * jextract generates the bindings per platform (C `long` is 64-bit on Linux, 32-bit on Windows), every GhosttyFX
 * artifact holds the same class names, and Amper cannot pick a JVM dependency per OS. So the Linux artifact is the
 * classpath dependency, and another platform's artifact ships as the resource `ghostty/ghosttyfx-<platform>.jar`:
 * the engine is then loaded with those bindings in a class loader of its own.
 */
internal object TerminalEngines {
    private const val FACTORY = "org.example.kotlineditor.server.ghostty.GhosttyEngineFactory"

    private val factory: TerminalEngineFactory by lazy { loadFactory() }

    fun create(
        columns: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
        writePty: (ByteArray) -> Unit,
    ): TerminalEngine = factory.create(columns, rows, cellWidthPx, cellHeightPx, writePty)

    private fun loadFactory(): TerminalEngineFactory {
        val parent = TerminalEngines::class.java.classLoader
        val bindings =
            TerminalEngines::class.java.getResource("/ghostty/ghosttyfx-${ghosttyPlatform()}.jar")
                ?: return Class.forName(FACTORY, true, parent).getDeclaredConstructor().newInstance()
                    as TerminalEngineFactory
        // URLClassLoader cannot read a jar nested in the server's jar.
        val jar = Files.createTempFile("ghosttyfx-", ".jar")
        jar.toFile().deleteOnExit()
        bindings.openStream().use { Files.copy(it, jar, StandardCopyOption.REPLACE_EXISTING) }
        val loader = GhosttyClassLoader(jar.toUri().toURL(), parent)
        return Class.forName(FACTORY, true, loader).getDeclaredConstructor().newInstance() as TerminalEngineFactory
    }
}

/**
 * Loads the engine and the bindings of [bindings] first, everything else from [parent].
 *
 * The engine's classes are defined again from the parent's bytes so they link against these bindings; so is
 * `GhosttyKeys`, which lives in the bindings package to reach its package-private class.
 */
private class GhosttyClassLoader(bindings: URL, parent: ClassLoader) : URLClassLoader(arrayOf(bindings), parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (!name.startsWith(ENGINE_PACKAGE) && !name.startsWith(BINDINGS_PACKAGE)) {
            return super.loadClass(name, resolve)
        }
        synchronized(getClassLoadingLock(name)) {
            val loaded = findLoadedClass(name) ?: defineOwn(name)
            if (resolve) resolveClass(loaded)
            return loaded
        }
    }

    private fun defineOwn(name: String): Class<*> {
        val path = name.replace('.', '/') + ".class"
        if (findResource(path) != null) return findClass(name)
        val bytes = parent.getResourceAsStream(path)?.use { it.readBytes() } ?: throw ClassNotFoundException(name)
        return defineClass(name, bytes, 0, bytes.size)
    }

    private companion object {
        const val ENGINE_PACKAGE = "org.example.kotlineditor.server.ghostty."
        const val BINDINGS_PACKAGE = "io.github.vlaaad.ghostty."
    }
}
