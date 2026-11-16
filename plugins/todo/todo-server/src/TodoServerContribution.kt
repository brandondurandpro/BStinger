package org.example.kotlineditor.todo.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.PluginMethod
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.Request
import org.example.kotlineditor.server.ServerContribution

private const val PLUGIN_ID = "todo"
private const val METHOD_NAME = "scan"
private const val ROOT = "root"
private const val PATH = "path"
private const val LINE = "line"
private const val TEXT = "text"

/** `plugin/todo/scan`: the TODO and FIXME markers of the project (not mutating: the scan only reads). */
private val SCAN = PluginMethod(PLUGIN_ID, METHOD_NAME, mutating = false)

/**
 * The server half of the bundled Todo feature, found by the server through `META-INF/services`: it answers
 * `plugin/todo/scan` with the TODO and FIXME markers of a project.
 */
class TodoServerContribution : ServerContribution {
    override fun handlers(services: LocalServices): Map<MethodSpec, Handler> {
        return mapOf(SCAN to { scan() })
    }
}

/** Scans the project rooted at the `root` param, off the request's thread (the walk touches the disk). */
private suspend fun Request.scan(): JsonElement =
    withContext(Dispatchers.IO) { scanMarkers(params.string(ROOT)) }

private fun scanMarkers(root: String): JsonElement =
    todoScan(root).markersJson()

/** The markers, as a JSON array of `{path, line, text}` (the path is relative to the root, with `/` separators). */
private fun List<TodoMarker>.markersJson(): JsonArray =
    buildJsonArray {
        for (marker in this@markersJson) {
            add(
                buildJsonObject {
                    put(PATH, marker.path)
                    put(LINE, marker.line)
                    put(TEXT, marker.text)
                },
            )
        }
    }
