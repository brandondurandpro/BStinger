package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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

private const val PLUGIN_ID = "todo"
private const val ROOT_PARAM = "root"
private const val PATH = "path"
private const val LINE = "line"
private const val TEXT = "text"
private const val DEFAULT_ERROR = "The scan failed"

/** `plugin/todo/scan`: the TODO and FIXME markers of the project (not mutating: the scan only reads). */
internal val SCAN_METHOD = PluginMethod(PLUGIN_ID, "scan", mutating = false)

/** One TODO or FIXME marker of the project. */
data class TodoItem(
    /** Path of the file that holds the marker, relative to the project root, with `/` separators. */
    val path: String,
    /** Line of the marker in its file, counted from 1. */
    val line: Int,
    /** Text of the line that holds the marker. */
    val text: String,
)

/**
 * The TODO and FIXME markers of a project, scanned by the server half of the plugin (`plugin/todo/scan`).
 * [refresh] asks for them again; a failed call (server unreachable) keeps the last list and explains why in
 * [error], so the panel never empties because the link went down.
 */
@Stable
class TodoState(
    /** The project scanned, for the root its server half walks. */
    private val root: FileEntry,
    /** The server the plugin's methods are called on. */
    private val pluginServer: PluginServer,
) {
    /** The markers found, in the order of the scan; empty until the first scan answered. */
    var items: List<TodoItem> by mutableStateOf(emptyList())
        private set

    /** True while a scan is in flight, so the panel shows it is working. */
    var loading by mutableStateOf(false)
        private set

    /** Why the last scan failed; null when it did not. */
    var error: String? by mutableStateOf(null)
        private set

    /** Asks the server half of the plugin to scan the project again. */
    suspend fun refresh() {
        loading = true
        try {
            val answer = pluginServer.call(SCAN_METHOD, scanParams())
            items = itemsOf(answer)
            error = null
        } catch (broken: IOException) {
            error = broken.message ?: DEFAULT_ERROR
        } finally {
            loading = false
        }
    }

    private fun scanParams(): JsonObject = buildJsonObject { put(ROOT_PARAM, root.path) }

    private fun itemsOf(answer: JsonElement): List<TodoItem> =
        answer.jsonArray.map { marker -> marker.todoItem() }

    private fun JsonElement.todoItem(): TodoItem {
        val fields = jsonObject
        val path = fields.string(PATH)
        val line = fields.int(LINE)
        val text = fields.string(TEXT)
        return TodoItem(path, line, text)
    }
}

/** The Todo state of a project (its TODO and FIXME markers), as [ProjectHost.service] gives it to the Todo UI. */
val TODO_SERVICE = ServiceKey<TodoState>("todo")

/**
 * The Todo state of [host], created on the first use: the plugin publishes its state itself, since the project
 * provides the states of the features built into it, not the ones of the plugins.
 */
fun ProjectHost.todoState(): TodoState {
    val existing = serviceOrNull(TODO_SERVICE)
    if (existing != null) return existing
    provide(TODO_SERVICE) { TodoState(root, pluginServer) }
    return service(TODO_SERVICE)
}
