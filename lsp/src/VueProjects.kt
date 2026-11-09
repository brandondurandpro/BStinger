package org.example.kotlineditor.lsp

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Which projects are Vue projects: the TypeScript server then loads the Vue plugin, at its start. A project that was
 * found to be one stays one, so that the server is not restarted back and forth as `.vue` files open and close.
 */
internal class VueProjects {
    private val known = ConcurrentHashMap.newKeySet<String>()

    /** A `.vue` file is open, or the `package.json` of [project] depends on Vue or Nuxt, or it did before. */
    fun used(project: String, files: List<String>): Boolean {
        val found = project in known || files.any { it.endsWith(".vue") } || dependsOnVue(project)
        if (found) known += project
        return found
    }

    private fun dependsOnVue(project: String): Boolean {
        val manifest = Paths.get(project).resolve("package.json")
        val text = runCatching { Files.readString(manifest) }.getOrDefault("")
        return DEPENDENCIES.any { it in text }
    }

    private companion object {
        val DEPENDENCIES = listOf("\"vue\"", "\"nuxt\"")
    }
}
