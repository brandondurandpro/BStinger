package org.example.kotlineditor.plugin.core

import java.io.File
import java.lang.ref.WeakReference
import java.net.URL
import java.net.URLClassLoader

private const val PLATFORM = "java.,javax.,jdk.,sun.,kotlin.,kotlinx.coroutines.,kotlinx.serialization."

/** What the client half of a plugin may use of the IDE: the plugin API, the UI kit, Compose, the platform. */
val CLIENT_VISIBLE: List<String> =
    PLATFORM.split(',') +
        listOf(
            "androidx.compose.",
            "org.jetbrains.skia.",
            "org.example.kotlineditor.plugin.",
            "org.example.kotlineditor.ui.",
            "org.example.kotlineditor.state.",
            "org.example.kotlineditor.model.",
            "org.example.kotlineditor.syntax.",
        )

/** What the server half of a plugin may use: the platform, the protocol and the server plugin API. No UI. */
val SERVER_VISIBLE: List<String> =
    PLATFORM.split(',') + listOf("org.example.kotlineditor.rpc.", "org.example.kotlineditor.plugin.server.")

/** The parent of the class loader of a plugin: the application's, filtered to the packages a plugin may see. */
private class SharedClassLoader(parent: ClassLoader, private val visible: List<String>) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (visible.none { name.startsWith(it) }) throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }

    override fun getResource(name: String): URL? = null
}

/** The class loader of one plugin: its own jars first-hand, the shared libraries through the filtered parent. */
class PluginClassLoader(jars: List<File>, parent: ClassLoader, visible: List<String>) :
    URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), SharedClassLoader(parent, visible))

private const val COLLECTION_ATTEMPTS = 10
private const val COLLECTION_PAUSE_MILLIS = 50L

/** What an unload found once the plugin's class loader should be gone. */
enum class UnloadOutcome {
    /** The class loader was collected. */
    Clean,

    /** Something still holds the class loader: running two versions side by side is unsafe, restart the IDE. */
    RestartRequired,
}

/**
 * Whether [reference] is cleared after the garbage collector ran. Collection is not immediate: it asks a few times,
 * calling [beforeEach] first (a UI still shows removed contributions until it recomposes). Waits, so not on a UI thread.
 */
fun collected(reference: WeakReference<*>, beforeEach: () -> Unit = {}): Boolean {
    repeat(COLLECTION_ATTEMPTS) {
        beforeEach()
        System.gc()
        if (reference.get() == null) return true
        Thread.sleep(COLLECTION_PAUSE_MILLIS)
    }
    return reference.get() == null
}

/** [collected] as an [UnloadOutcome]. */
fun outcomeOf(reference: WeakReference<*>, beforeEach: () -> Unit = {}): UnloadOutcome =
    if (collected(reference, beforeEach)) UnloadOutcome.Clean else UnloadOutcome.RestartRequired
