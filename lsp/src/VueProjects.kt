package org.example.kotlineditor.lsp

import java.nio.file.Files
import java.nio.file.Paths

/** Whether a project is a Vue project: the TypeScript server then loads the Vue plugin, at its start. */
internal object VueProjects {
    private val dependencies = listOf("\"vue\"", "\"nuxt\"")

    /** A `.vue` file is open, or the `package.json` of [project] depends on Vue or Nuxt. */
    fun used(project: String, files: List<String>): Boolean = files.any { it.endsWith(".vue") } || dependsOnVue(project)

    private fun dependsOnVue(project: String): Boolean {
        val manifest = Paths.get(project).resolve("package.json")
        val text = runCatching { Files.readString(manifest) }.getOrDefault("")
        return dependencies.any { it in text }
    }
}
