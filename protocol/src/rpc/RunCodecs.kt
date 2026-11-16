package org.example.kotlineditor.rpc

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.ProjectRunner
import org.example.kotlineditor.model.RunChunk
import org.example.kotlineditor.model.RunManifest
import org.example.kotlineditor.model.RunStream
import java.io.IOException

private const val LINES = "lines"
private const val FINISHED = "finished"
private const val EXIT_CODE = "exitCode"
private const val ERROR = "error"

private const val DIRECTORY = "directory"
private const val ROOT = "root"
private const val STREAM = "stream"

/** One chunk of a run; the fields at their default value are left out. */
fun RunChunk.toJson(): JsonObject =
    buildJsonObject {
        put("seq", seq)
        if (lines.isNotEmpty()) put(LINES, JsonArray(lines.map(::JsonPrimitive)))
        if (finished) put(FINISHED, true)
        if (exitCode != null) put(EXIT_CODE, exitCode)
        if (error != null) put(ERROR, error)
    }

/** Reads a chunk written by [RunChunk.toJson]. */
fun JsonElement.runChunk(): RunChunk {
    val chunk = jsonObject
    val lines = chunk[LINES]?.jsonArray?.map { it.string() }.orEmpty()
    return RunChunk(
        seq = chunk.long("seq"),
        lines = lines,
        finished = chunk[FINISHED]?.let { chunk.bool(FINISHED) } ?: false,
        exitCode = chunk[EXIT_CODE]?.let { chunk.int(EXIT_CODE) },
        error = chunk.stringOrNull(ERROR),
    )
}

/**
 * [ProjectRunner] of the server. The request id of RunStart is the id of the run's chunk stream, registered before
 * the request is sent so no chunk can arrive unlistened; the stream resumes by itself after a reconnection.
 */
class RemoteRunner(private val client: RpcClient) : ProjectRunner {
    override suspend fun manifests(root: String): List<RunManifest> {
        val result = client.call(Method.RunManifests) { put(ROOT, root) }
        return result.jsonArray.map { element ->
            val fields = element.jsonObject
            RunManifest(
                fileName = fields.string("fileName"),
                directory = fields.string("directory"),
                content = fields.string("content"),
                siblings = fields.read("siblings") { it.strings() }.toSet(),
            )
        }
    }

    override fun start(directory: String, command: List<String>): RunStream {
        val id = client.newId()
        val chunks = client.openRunStream(id)
        client.scope.launch {
            try {
                client.call(Method.RunStart, id = id) {
                    put(DIRECTORY, directory)
                    put("command", command.toJson())
                }
            } catch (_: IOException) {
                // The client closed, or the server (restart? no session any more): end the stream cleanly.
                client.closeStream(id)
            }
        }
        return RunStream(id, flow { for (chunk in chunks) emit(chunk) })
    }

    override fun stop(stream: RunStream) {
        client.scope.launch { runCatching { client.call(Method.RunStop) { put(STREAM, stream.id) } } }
    }
}
