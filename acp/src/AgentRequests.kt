package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import java.io.File

private const val INVALID_PARAMS = -32_602
private const val OUTCOME = "outcome"

/**
 * What an agent asks its client: the user's permission, the session it belongs to answers; a file to read or
 * write, [files] does. A request of a session the client does not know, or with a relative path, is refused.
 */
internal class AgentRequests(private val sessions: Map<String, SessionListener>, private val files: FileAccess) {
    /** Answers [method] with [params], or refuses it. */
    suspend fun answer(method: String, params: JsonElement?): JsonElement {
        val request = params.obj() ?: throw LspResponseException(INVALID_PARAMS, "Params must be an object")
        return when (method) {
            "session/request_permission" -> permission(request)
            "fs/read_text_file" -> read(request)
            "fs/write_text_file" -> write(request)
            else -> throw LspResponseException(LspConnection.METHOD_NOT_FOUND, "Unhandled method $method")
        }
    }

    private suspend fun permission(request: JsonObject): JsonElement {
        val session = sessionOf(request)
        val chosen = session.requestPermission(request)
        return buildJsonObject {
            putJsonObject(OUTCOME) {
                if (chosen == null) {
                    put(OUTCOME, "cancelled")
                } else {
                    put(OUTCOME, "selected")
                    put("optionId", chosen)
                }
            }
        }
    }

    private fun read(request: JsonObject): JsonElement {
        sessionOf(request)
        val path = pathOf(request)
        val line = request["line"]?.jsonPrimitive?.intOrNull
        val limit = request["limit"]?.jsonPrimitive?.intOrNull
        val text = files.read(path)
        val whole = line == null && limit == null
        val content = if (whole) text else slice(text, line ?: 1, limit)
        return buildJsonObject { put("content", content) }
    }

    private fun write(request: JsonObject): JsonElement {
        sessionOf(request)
        val content = request.text("content") ?: throw LspResponseException(INVALID_PARAMS, "Missing content")
        files.write(pathOf(request), content)
        return JsonObject(emptyMap())
    }

    private fun sessionOf(request: JsonObject): SessionListener {
        val id = request.text(SESSION_ID)
        val session = id?.let(sessions::get)
        return session ?: throw LspResponseException(INVALID_PARAMS, "Unknown session $id")
    }

    private fun pathOf(request: JsonObject): String {
        val path = request.text("path") ?: throw LspResponseException(INVALID_PARAMS, "Missing path")
        if (!File(path).isAbsolute) throw LspResponseException(INVALID_PARAMS, "The path must be absolute: $path")
        return path
    }
}

// Lines [line] (1-based) to [line] + [limit] of [text].
private fun slice(text: String, line: Int, limit: Int?): String {
    val lines = text.split("\n")
    val from = (line - 1).coerceIn(0, lines.size)
    val to = if (limit == null) lines.size else (from + limit).coerceAtMost(lines.size)
    return lines.subList(from, to).joinToString("\n")
}
