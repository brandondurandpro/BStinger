package org.example.kotlineditor.lsp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/** Where the Tailwind CSS server runs: the projects, or parts of a project, that use Tailwind CSS. */
internal object TailwindProjects {
    private val configs =
        listOf("tailwind.config.js", "tailwind.config.cjs", "tailwind.config.mjs", "tailwind.config.ts")

    /**
     * The closest directory between [file] and [project] that holds a `tailwind.config.*`, else [project] when its
     * `package.json` depends on `tailwindcss` (version 4 has no config file); null when the project does not use it.
     */
    fun rootOf(file: String, project: String): String? {
        val top = Paths.get(project).toAbsolutePath().normalize()
        val absolute = Paths.get(file).toAbsolutePath()
        val start = absolute.normalize().parent
        val configured = closestConfigured(start, top)
        val found = configured?.let { if (it == top) project else it.toString() }
        return found ?: project.takeIf { dependsOnTailwind(top) }
    }

    private fun closestConfigured(start: Path?, top: Path): Path? {
        var directory = start
        while (directory != null && isSearched(directory, top)) {
            directory = directory.parent
        }
        return directory?.takeIf { it.startsWith(top) }
    }

    // Still inside the project, and without a config: the search goes on to the parent directory.
    private fun isSearched(directory: Path, top: Path): Boolean = directory.startsWith(top) && !hasConfig(directory)

    private fun hasConfig(directory: Path): Boolean = configs.any { Files.isRegularFile(directory.resolve(it)) }

    private fun dependsOnTailwind(project: Path): Boolean {
        val manifest = project.resolve("package.json")
        val text = runCatching { Files.readString(manifest) }.getOrDefault("")
        return "\"tailwindcss\"" in text
    }
}
