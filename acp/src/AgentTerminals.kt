package org.example.kotlineditor.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

private const val INVALID_PARAMS = -32_602
private const val TERMINAL_ID = "terminalId"
private const val EXIT_CODE = "exitCode"
private const val SIGNAL = "signal"

/**
 * The commands the agent of one process runs on the server (the `terminal/` requests), by id: started in the
 * directory of their session (or the one the agent gives), they run until they exit, are killed, or their session
 * ends.
 */
internal class AgentTerminals(private val scope: CoroutineScope) {
    private val terminals = ConcurrentHashMap<String, AgentTerminal>()
    private val ids = AtomicLong()

    /** The terminal [id], null when there is none (any more). */
    operator fun get(id: String): AgentTerminal? = terminals[id]

    /** Answers the request [method] of [session], whose updates go to [listener]. */
    suspend fun answer(method: String, request: JsonObject, session: String, listener: SessionListener): JsonElement =
        when (method) {
            "terminal/create" -> create(request, session, listener)
            "terminal/output" -> output(terminalOf(request, session))
            "terminal/wait_for_exit" -> exitStatus(terminalOf(request, session).waitForExit())
            "terminal/kill" -> empty { terminalOf(request, session).kill() }
            "terminal/release" -> empty { release(terminalOf(request, session)) }
            else -> throw LspResponseException(LspConnection.METHOD_NOT_FOUND, "Unhandled method $method")
        }

    /** Kills and forgets the terminals of [session], when it ends. */
    fun releaseSession(session: String) {
        terminals.values.filter { it.session == session }.forEach(::release)
    }

    /** Kills every terminal, when the agent process stops. */
    fun close() {
        terminals.values.toList().forEach(::release)
    }

    private fun create(request: JsonObject, session: String, listener: SessionListener): JsonElement {
        val command = request.text("command") ?: throw LspResponseException(INVALID_PARAMS, "Missing command")
        val arguments = request["args"].array().mapNotNull { (it as? JsonPrimitive)?.content }
        val directory = request.text("cwd") ?: listener.directory()
        val limit = request["outputByteLimit"]?.jsonPrimitive?.longOrNull
        val process =
            try {
                ProcessBuilder(commandLine(listOf(command) + arguments))
                    .directory(directory?.let(::File))
                    .redirectErrorStream(true)
                    .apply { environment().putAll(request.environment()) }
                    .start()
            } catch (failure: IOException) {
                val refused = LspResponseException(INVALID_PARAMS, "Cannot run $command: ${failure.message}")
                throw refused.apply { initCause(failure) }
            }
        process.outputStream.close()
        val id = "terminal-${ids.incrementAndGet()}"
        terminals[id] = AgentTerminal(id, session, process, limit, scope, listener::onTerminal)
        return buildJsonObject { put(TERMINAL_ID, id) }
    }

    private fun output(terminal: AgentTerminal): JsonElement {
        val snapshot = terminal.output()
        return buildJsonObject {
            put("output", snapshot.text)
            put("truncated", snapshot.truncated)
            val code = snapshot.exitCode
            if (code != null) {
                putJsonObject("exitStatus") {
                    put(EXIT_CODE, code)
                    put(SIGNAL, JsonNull)
                }
            }
        }
    }

    private fun exitStatus(code: Int): JsonElement =
        buildJsonObject {
            put(EXIT_CODE, code)
            put(SIGNAL, JsonNull)
        }

    private fun release(terminal: AgentTerminal) {
        terminal.kill()
        terminals.remove(terminal.id)
    }

    // The terminal of the request, which must belong to its session.
    private fun terminalOf(request: JsonObject, session: String): AgentTerminal {
        val id = request.text(TERMINAL_ID)
        val terminal = id?.let(terminals::get)?.takeIf { it.session == session }
        return terminal ?: throw LspResponseException(INVALID_PARAMS, "Unknown terminal $id")
    }
}

private inline fun empty(action: () -> Unit): JsonElement {
    action()
    return JsonObject(emptyMap())
}

// `env`: a list of `{name, value}`.
private fun JsonObject.environment(): Map<String, String> =
    get("env").array().mapNotNull { it.obj() }.associate { it.text("name").orEmpty() to it.text("value").orEmpty() }
