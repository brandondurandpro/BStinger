package org.example.kotlineditor.plugin.core

import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest

/** The file of an installed plugin that describes it, and the markers a half leaves beside it. */
const val PLUGIN_MANIFEST = "plugin.yaml"
const val PLUGIN_LOADING_MARKER = ".loading"
const val PLUGIN_DISABLED_MARKER = ".disabled"

/** The outcome of taking a half down: its code is collectable, or something still holds it. */
/**
 * Closes the [registration] of a running half, runs its [cleanups] and closes its [loader], and answers the weak
 * reference its code is checked through. The caller runs the check in its own frame, **after this one has returned**:
 * a frame that still holds the loader in a local would keep it alive through every collection attempt.
 */
fun shutDownHalf(registration: AutoCloseable, cleanups: List<AutoCloseable>, loader: PluginClassLoader): WeakReference<PluginClassLoader> {
    registration.close()
    cleanups.forEach { runCatching { it.close() } }
    val reference = WeakReference(loader)
    loader.close()
    return reference
}

/**
 * The lifecycle one half of a plugin (the client half in the IDE, the server half in the server) runs through:
 * started under the folder's `.loading` marker, which a start that never finished leaves behind so the next start
 * keeps the plugin off, and taken down by [shutDownHalf], then checked for the code to be collectable. The check
 * runs in the frame of the caller, not here: a frame that still holds the loader would keep it alive through every
 * collection attempt.
 */
class PluginHalves(private val beforeCollect: () -> Unit = {}) {
    /** The plugin folders of [directory], by id. */
    fun foldersOf(directory: File): List<File> =
        directory.listFiles { file -> file.isDirectory }.orEmpty().sortedBy { it.name }

    /** Runs [start] under [folder]'s `.loading` marker; a null answer, or a start that throws, leaves the marker. */
    fun <T> guardedStart(folder: File, version: String?, start: () -> T?): T? {
        val marker = File(folder, PLUGIN_LOADING_MARKER)
        marker.writeText(version.orEmpty())
        val result = start()
        // A null answer or a start that threw or hung leaves the marker, and the next start keeps the plugin off.
        if (result != null) marker.delete()
        return result
    }

    /** Takes them down without the check: the IDE or the server is closing, or the UI already is. */
    fun shutDown(registration: AutoCloseable, cleanups: List<AutoCloseable>, loader: PluginClassLoader) {
        shutDownHalf(registration, cleanups, loader)
    }

    /** Loads [className] from [loader] and instantiates it with no argument. */
    fun instantiate(loader: PluginClassLoader, className: String): Any =
        loader.loadClass(className).getDeclaredConstructor().newInstance()
}

/** The SHA-256 of [bytes], lowercase hexadecimal: what a transferred jar is checked against. */
fun sha256Of(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
