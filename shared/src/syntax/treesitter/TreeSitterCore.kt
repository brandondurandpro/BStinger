package org.example.kotlineditor.syntax.treesitter

import io.github.treesitter.jtreesitter.NativeLibraryLookup
import io.github.treesitter.jtreesitter.Parser
import java.io.File
import java.io.IOException
import java.lang.foreign.Arena
import java.lang.foreign.Linker
import java.lang.foreign.SymbolLookup
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * The tree-sitter runtime library, bundled for every platform as `/tree-sitter/runtime/<platform>-tree-sitter.<ext>`
 * (built by `tools/tree-sitter/build-runtime`, which exports its C API on every platform), and found by jtreesitter
 * through [TreeSitterLibraryLookup]. It is copied out of the jar into [directory] before being loaded.
 */
object TreeSitterCore {
    private const val HASH_BYTES = 8

    /** Where the library is copied; set by [GrammarStore] before the first parser is created. */
    @Volatile
    var directory: File = File(System.getProperty("java.io.tmpdir"), "kotlin-editor-tree-sitter")

    // Why the runtime does not load (no library for this platform, another libc...), null when it does. A failure of
    // the lookup reaches here wrapped in the ExceptionInInitializerError of jtreesitter's bindings.
    private val loadFailure: Throwable? by lazy {
        try {
            Parser().close()
            null
        } catch (e: LinkageError) {
            e
        } catch (e: IllegalStateException) {
            e
        } catch (e: IllegalArgumentException) {
            e
        } catch (_: RuntimeException) {
            IllegalStateException("The tree-sitter runtime failed to start")
        }
    }

    /** Whether tree-sitter works here: loads the library on the first call. */
    val isAvailable: Boolean get() = loadFailure == null

    /** Why tree-sitter does not work here, `null` when it does. */
    val failure: Throwable? get() = loadFailure

    /** The library file, copied out of the classpath once (its name carries a hash of its content). */
    internal fun library(): File {
        val platform = NativePlatform.current ?: throw IOException("No tree-sitter library for this platform")
        val resource = "/tree-sitter/runtime/${platform.id}-tree-sitter.${platform.libraryExtension}"
        val bytes =
            TreeSitterCore::class.java.getResourceAsStream(resource)?.use { it.readBytes() }
                ?: throw IOException("Missing $resource")
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hash = digest.take(HASH_BYTES).joinToString("") { "%02x".format(it) }
        val target = File(directory, "core-$hash/${resource.substringAfterLast('/')}")
        if (target.length() != bytes.size.toLong()) writeAtomically(target, bytes)
        return target
    }
}

/**
 * Found by jtreesitter with a [java.util.ServiceLoader]: points it at the library of [TreeSitterCore]. jtreesitter also
 * looks up `malloc`, `calloc` and `free`: on Linux and macOS the library resolves them through its dependencies, but a
 * Windows DLL only exports its own functions, so they come from the C runtime of the JVM's default lookup (the UCRT
 * the library uses too).
 */
class TreeSitterLibraryLookup : NativeLibraryLookup {
    override fun get(arena: Arena): SymbolLookup =
        SymbolLookup
            .libraryLookup(TreeSitterCore.library().toPath(), arena)
            .or(Linker.nativeLinker().defaultLookup())
}

/** Writes next to [target] then renames, so that another IDE running at the same time never loads half a file. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    val parent = target.absoluteFile.parentFile
    parent.mkdirs()
    val temp = File.createTempFile(target.name, ".tmp", parent)
    try {
        temp.writeBytes(bytes)
        try {
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (e: IOException) {
            // Windows cannot replace a library another process has loaded: the file in place is then the same one.
            if (!target.isFile) throw e
        }
    } finally {
        temp.delete()
    }
}
