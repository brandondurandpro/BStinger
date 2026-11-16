package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.PluginServer
import org.example.kotlineditor.rpc.PluginMethod
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.string
import java.io.IOException

private const val PLUGIN_ID = "editorconfig"
private const val ROOT_PARAM = "root"
private const val PATHS_PARAM = "paths"
private const val PATH = "path"
private const val LINE = "line"
private const val RULE = "rule"
private const val MESSAGE = "message"
private const val DEFAULT_ERROR = "The check failed"

/** `plugin/editorconfig/check`: the rules the files break (not mutating: the check only reads). */
internal val CHECK_METHOD = PluginMethod(PLUGIN_ID, "check", mutating = false)

/** One rule a file breaks, as the server half of the plugin reports it. */
data class EditorConfigViolation(
    /** Path of the file, relative to the project root, with `/` separators. */
    val path: String,
    /** Line the violation is about, counted from 1; 0 when it is about the whole file. */
    val line: Int,
    /** The rule broken: `indent-style`, `indent-size`, `end-of-line` or `final-newline`. */
    val rule: String,
    val message: String,
)

/**
 * The violations of `.editorconfig` the open files hold, checked by the server half of the plugin
 * (`plugin/editorconfig/check`). [refresh] asks for the named files again; a failed call (server unreachable) keeps
 * the last list and explains why in [error], so the panel never empties because the link went down.
 */
@Stable
class EditorConfigState(
    /** The project checked, for the root its server half reads `.editorconfig` at. */
    private val root: FileEntry,
    /** The server the plugin's methods are called on. */
    private val pluginServer: PluginServer,
) {
    /** The violations found, in the order of the check; empty until the first check answered. */
    var violations: List<EditorConfigViolation> by mutableStateOf(emptyList())
        private set

    /** True while a check is in flight, so the panel shows it is working. */
    var loading by mutableStateOf(false)
        private set

    /** Why the last check failed; null when it did not. */
    var error: String? by mutableStateOf(null)
        private set

    /** Asks the server half of the plugin to check [paths] (relative to the root) again. */
    suspend fun refresh(paths: List<String>) {
        loading = true
        try {
            val answer = pluginServer.call(CHECK_METHOD, checkParams(paths))
            violations = violationsOf(answer)
            error = null
        } catch (broken: IOException) {
            error = broken.message ?: DEFAULT_ERROR
        } finally {
            loading = false
        }
    }

    private fun checkParams(paths: List<String>): JsonObject =
        buildJsonObject {
            put(ROOT_PARAM, root.path)
            put(PATHS_PARAM, buildJsonArray { for (path in paths) add(JsonPrimitive(path)) })
        }

    private fun violationsOf(answer: JsonElement): List<EditorConfigViolation> =
        answer.jsonArray.map { element -> element.violation() }

    private fun JsonElement.violation(): EditorConfigViolation {
        val fields = jsonObject
        val path = fields.string(PATH)
        val line = fields.int(LINE)
        val rule = fields.string(RULE)
        val message = fields.string(MESSAGE)
        return EditorConfigViolation(path, line, rule, message)
    }
}

/** The EditorConfig state of a project, as [ProjectHost.service] gives it to the EditorConfig UI. */
val EDITORCONFIG_SERVICE = ServiceKey<EditorConfigState>("editorconfig")

/**
 * The EditorConfig state of [host], created on the first use: the plugin publishes its state itself, since the
 * project provides the states of the features built into it, not the ones of the plugins.
 */
fun ProjectHost.editorConfigState(): EditorConfigState {
    val existing = serviceOrNull(EDITORCONFIG_SERVICE)
    if (existing != null) return existing
    provide(EDITORCONFIG_SERVICE) { EditorConfigState(root, pluginServer) }
    return service(EDITORCONFIG_SERVICE)
}
