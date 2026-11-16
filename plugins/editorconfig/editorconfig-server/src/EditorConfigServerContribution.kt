package org.example.kotlineditor.editorconfig.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.PluginMethod
import org.example.kotlineditor.rpc.field
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.Request
import org.example.kotlineditor.server.ServerContribution
import java.nio.file.Paths

private const val PLUGIN_ID = "editorconfig"
private const val METHOD_NAME = "check"
private const val ROOT = "root"
private const val PATHS = "paths"
private const val PATH = "path"
private const val LINE = "line"
private const val RULE = "rule"
private const val MESSAGE = "message"

/** `plugin/editorconfig/check`: the rules of `.editorconfig` the files break (not mutating: the check only reads). */
private val CHECK = PluginMethod(PLUGIN_ID, METHOD_NAME, mutating = false)

/**
 * The server half of the bundled EditorConfig feature, found by the server through `META-INF/services`: it answers
 * `plugin/editorconfig/check` with the violations of the named files against the `.editorconfig` of the root.
 */
class EditorConfigServerContribution : ServerContribution {
    override fun handlers(services: LocalServices): Map<MethodSpec, Handler> {
        return mapOf(CHECK to { check() })
    }
}

/** Checks the files off the request's thread (the check touches the disk). */
private suspend fun Request.check(): JsonElement =
    withContext(Dispatchers.IO) {
        val root = Paths.get(params.string(ROOT))
        val paths = params.field(PATHS).jsonArray.map { path -> path.string() }
        checkFiles(root, paths).violationsJson()
    }

/** The violations, as a JSON array of `{path, line, rule, message}` (the path is relative, with `/` separators). */
private fun List<EditorConfigViolation>.violationsJson(): JsonArray =
    buildJsonArray {
        for (violation in this@violationsJson) {
            add(
                buildJsonObject {
                    put(PATH, violation.path)
                    put(LINE, violation.line)
                    put(RULE, violation.rule)
                    put(MESSAGE, violation.message)
                },
            )
        }
    }
