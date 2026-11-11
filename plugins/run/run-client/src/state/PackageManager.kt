package org.example.kotlineditor.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The package managers whose `package.json` scripts can be run, with the lock file that tells a project uses one. */
enum class PackageManager(val label: String, val program: String, val lockFiles: List<String>) {
    Pnpm("pnpm", "pnpm", listOf("pnpm-lock.yaml")),
    Yarn("Yarn", "yarn", listOf("yarn.lock")),
    Bun("Bun", "bun", listOf("bun.lockb", "bun.lock")),
    Npm("npm", "npm", listOf("package-lock.json")),
}

/** The names of the scripts a `package.json` declares, in the order of the file. Invalid JSON or no scripts: none. */
fun packageScriptNames(json: String): List<String> {
    val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
    val scripts = root?.get("scripts") as? JsonObject
    return scripts?.keys?.toList().orEmpty()
}

/** The package manager of a project whose directory holds [siblings] (file names): by its lock file, npm otherwise. */
fun packageManagerOf(siblings: Set<String>): PackageManager {
    val found = PackageManager.entries.firstOrNull { manager -> manager.lockFiles.any { it in siblings } }
    return found ?: PackageManager.Npm
}

/**
 * The run configurations of the scripts of the Node project in [directory] (relative to the project, blank for its
 * root): one per script, run with `<manager> run <script>`. [windows] picks the `.cmd` shims of npm, Yarn and pnpm,
 * which a Windows server finds on its path.
 */
fun packageConfigurations(
    directory: String,
    scripts: List<String>,
    manager: PackageManager,
    windows: Boolean,
): List<RunConfiguration> {
    val shim = windows && manager != PackageManager.Bun
    val program = if (shim) "${manager.program}.cmd" else manager.program
    return scripts.map { script ->
        val argument = if (script.any { it.isWhitespace() }) "\"$script\"" else script
        RunConfiguration(
            id = "package:$directory:$script",
            name = "${manager.label}: $script",
            kind = RunKind.Run,
            commandLine = "$program run $argument",
            workingDirectory = directory,
        )
    }
}

/** Whether [path] (relative to the project, with `/`) is the `package.json` of the project, not of a dependency. */
fun isProjectPackageFile(path: String): Boolean {
    val parts = path.split('/')
    val vendored = parts.dropLast(1).any { it == "node_modules" || it == "vendor" }
    return parts.lastOrNull() == "package.json" && !vendored
}
