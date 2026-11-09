package org.example.kotlineditor.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// The scripts Composer runs by itself at some step (`composer install`, `composer update`...): not meant to be run.
private val EVENT_PREFIXES = listOf("pre-", "post-")

/**
 * The names of the scripts a `composer.json` declares, in the order of the file, without the ones Composer runs by
 * itself (`post-install-cmd`...). A file that is not valid JSON, or has no scripts, has none.
 */
fun composerScriptNames(json: String): List<String> {
    val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject
    val scripts = root?.get("scripts") as? JsonObject
    return scripts?.keys?.filter { name -> EVENT_PREFIXES.none { name.startsWith(it) } }.orEmpty()
}

/**
 * The run configurations of the scripts of the Composer project in [directory] (relative to the project, blank for
 * its root): one per script, run with `composer run-script`. [windows] picks `composer.bat`, which a Windows server
 * finds on its path, the way a shell would.
 */
fun composerConfigurations(directory: String, scripts: List<String>, windows: Boolean): List<RunConfiguration> {
    val program = if (windows) "composer.bat" else "composer"
    val where = if (directory.isBlank()) "" else " ($directory)"
    return scripts.map { script ->
        val argument = if (script.any { it.isWhitespace() }) "\"$script\"" else script
        RunConfiguration(
            id = "composer:$directory:$script",
            name = "Composer: $script$where",
            kind = RunKind.Run,
            commandLine = "$program run-script $argument",
            workingDirectory = directory,
        )
    }
}

/** Whether [path] (relative to the project, with `/`) is a `composer.json` of the project itself, not of a vendor. */
fun isProjectComposerFile(path: String): Boolean {
    val parts = path.split('/')
    val vendored = parts.dropLast(1).any { it == "vendor" || it == "node_modules" }
    return parts.lastOrNull() == "composer.json" && !vendored
}
