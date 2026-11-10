package org.example.kotlineditor.plugin.host

import java.io.File
import java.net.URL
import java.net.URLClassLoader

// What a plugin may use of the IDE: the plugin API and what it exposes, the UI kit, Compose, coroutines,
// serialization, and the platform. Anything else of the IDE (the server, the protocol client...) is not visible.
private val SHARED_PREFIXES =
    listOf(
        "java.",
        "javax.",
        "jdk.",
        "sun.",
        "kotlin.",
        "kotlinx.coroutines.",
        "kotlinx.serialization.",
        "androidx.compose.",
        "org.jetbrains.skia.",
        "org.example.kotlineditor.plugin.",
        "org.example.kotlineditor.ui.",
        "org.example.kotlineditor.state.",
        "org.example.kotlineditor.model.",
        "org.example.kotlineditor.syntax.",
    )

/** The parent of the class loader of a plugin: the application's, filtered to what a plugin may see. */
private class SharedClassLoader(parent: ClassLoader) : ClassLoader(parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (SHARED_PREFIXES.none { name.startsWith(it) }) throw ClassNotFoundException(name)
        return super.loadClass(name, resolve)
    }

    override fun getResource(name: String): URL? = null
}

/** The class loader of one plugin: its own jars first-hand, the shared libraries through the filtered parent. */
class PluginClassLoader(jars: List<File>, parent: ClassLoader) :
    URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), SharedClassLoader(parent))
