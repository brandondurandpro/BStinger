package org.example.kotlineditor.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.jsonrpc.LineFraming
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.supervisor.LogRing
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.rpc.Protocol
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/** The version of ACP this client speaks. */
internal const val ACP_VERSION = 1

private const val INVALID_PARAMS = -32_602
private const val EXIT_WAIT_MS = 1_000L
private const val STDERR_LINES = 200
private const val SESSION_ID = "sessionId"

/** What an agent said about itself when it started. */
internal class AgentInfo(val name: String?, val authMethods: List<String>)

/** What a session receives from its agent: its updates and its permission requests. */
internal interface SessionListener {
    fun onUpdate(update: JsonObject)

    /** Asks the user; returns the option chosen, null when the prompt was cancelled first. */
    suspend fun requestPermission(params: JsonObject): String?
}

/**
 * One agent process and its ACP connection, shared by the sessions of every conversation that uses the same
 * [command]. It is the client side of ACP: it answers the agent's file system requests with [files] and forwards
 * updates and permission requests to the session they belong to.
 */
internal class AgentProcess(
    val command: AgentCommand,
    private val scope: CoroutineScope,
    private val files: FileAccess,
) {
    private val log = LogRing(STDERR_LINES)
    private val sessions = ConcurrentHashMap<String, SessionListener>()
    private val start = Mutex()
    private var info: AgentInfo? = null
    private val exited = CompletableDeferred<Unit>()

    private val process: Process =
        try {
            ProcessBuilder(commandLine(command.command))
                .directory(File(System.getProperty("user.home")))
                .apply { environment().putAll(command.environment) }
                .start()
        } catch (failure: IOException) {
            throw IOException("Cannot start the agent ${command.command.joinToString(" ")}: ${failure.message}", failure)
        }

    private val connection =
        LspConnection(
            process.inputStream,
            process.outputStream,
            scope,
            onRequest = ::onRequest,
            onNotification = ::onNotification,
            onClose = { exited.complete(Unit) },
            framing = LineFraming,
            cancelMethod = "$/cancel_request",
            peer = "The agent connection",
        )

    init {
        thread(name = "agent-stderr", isDaemon = true) {
            runCatching { process.errorStream.bufferedReader().forEachLine(log::add) }
        }
        connection.start()
    }

    /** Whether the process still answers. */
    val isAlive: Boolean get() = !connection.isClosed && process.isAlive

    /** Why the agent stopped: its exit code and the last line it wrote on stderr. */
    fun exitReason(): String {
        val code = if (process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) " (exit code ${process.exitValue()})" else ""
        val last = log.last()?.let { ": $it" }.orEmpty()
        return "The agent stopped$code$last"
    }

    /** Initializes the connection on first use, and returns what the agent said about itself. */
    suspend fun ready(): AgentInfo =
        start.withLock {
            info ?: initialize().also { info = it }
        }

    private suspend fun initialize(): AgentInfo {
        val answer = connection.request("initialize", initializeParams()).obj()
        val protocolVersion = answer?.get("protocolVersion")?.jsonPrimitive?.intOrNull
        if (protocolVersion != ACP_VERSION) {
            throw IOException("The agent speaks ACP version $protocolVersion, not $ACP_VERSION")
        }
        val agent = answer["agentInfo"].obj()
        val title = agent?.text("title") ?: agent?.text("name")
        val agentVersion = agent?.text("version")
        val name = title?.let { listOfNotNull(it, agentVersion).joinToString(" ") }
        val methods =
            answer["authMethods"].array().mapNotNull { it.obj() }.map { method ->
                listOfNotNull(method.text("name"), method.text("description")).joinToString(": ")
            }
        return AgentInfo(name, methods)
    }

    /** Starts a session working in [directory], whose updates go to [listener]; returns its id. */
    suspend fun newSession(directory: String, listener: SessionListener): String {
        val params =
            buildJsonObject {
                put("cwd", directory)
                put("mcpServers", JsonArray(emptyList()))
            }
        val answer = connection.request("session/new", params).obj()
        val id = answer?.text(SESSION_ID) ?: throw IOException("The agent started a session without an id")
        sessions[id] = listener
        return id
    }

    /** Sends [text] to [session] and waits for the end of the turn; returns why it ended (`end_turn`, `cancelled`). */
    suspend fun prompt(session: String, text: String): String {
        val params =
            buildJsonObject {
                put(SESSION_ID, session)
                put(
                    "prompt",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text)
                            },
                        )
                    },
                )
            }
        val answer = connection.request("session/prompt", params).obj()
        return answer?.text("stopReason") ?: "end_turn"
    }

    /** Asks the agent to stop the turn of [session]; it answers the prompt with `cancelled`. */
    fun cancel(session: String) {
        runCatching { connection.notify("session/cancel", buildJsonObject { put(SESSION_ID, session) }) }
    }

    /** Forgets [session]: its updates are ignored from now on. */
    fun release(session: String) {
        sessions.remove(session)
    }

    /** Whether no session uses the process any more. */
    val unused: Boolean get() = sessions.isEmpty()

    /** Stops the process and the programs it started (npx starts node). */
    fun destroy() {
        connection.close()
        process.descendants().forEach { it.destroy() }
        process.destroy()
    }

    private fun onNotification(method: String, params: JsonElement?) {
        if (method != "session/update") return
        val update = params.obj() ?: return
        val session = update.text(SESSION_ID)?.let(sessions::get) ?: return
        update["update"].obj()?.let(session::onUpdate)
    }

    private suspend fun onRequest(method: String, params: JsonElement?): JsonElement {
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
            putJsonObject("outcome") {
                if (chosen == null) {
                    put("outcome", "cancelled")
                } else {
                    put("outcome", "selected")
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
        val content = if (line == null && limit == null) text else slice(text, line ?: 1, limit)
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
        return id?.let(sessions::get) ?: throw LspResponseException(INVALID_PARAMS, "Unknown session $id")
    }

    private fun pathOf(request: JsonObject): String {
        val path = request.text("path") ?: throw LspResponseException(INVALID_PARAMS, "Missing path")
        if (!File(path).isAbsolute) throw LspResponseException(INVALID_PARAMS, "The path must be absolute: $path")
        return path
    }

    private fun initializeParams() =
        buildJsonObject {
            put("protocolVersion", ACP_VERSION)
            putJsonObject("clientCapabilities") {
                putJsonObject("fs") {
                    put("readTextFile", true)
                    put("writeTextFile", true)
                }
                put("terminal", false)
            }
            putJsonObject("clientInfo") {
                put("name", "kotlin-editor")
                put("title", "Kotlin Editor")
                put("version", Protocol.APP_VERSION)
            }
        }
}

// Lines [line] (1-based) to [line] + [limit] of [text].
private fun slice(text: String, line: Int, limit: Int?): String {
    val lines = text.split("\n")
    val from = (line - 1).coerceIn(0, lines.size)
    val to = if (limit == null) lines.size else (from + limit).coerceAtMost(lines.size)
    return lines.subList(from, to).joinToString("\n")
}

// Windows starts `npx` (a .cmd script) only through cmd.exe; an .exe starts directly.
private fun commandLine(command: List<String>): List<String> {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val program = command.firstOrNull()?.lowercase().orEmpty()
    val direct = program.endsWith(".exe") || program.endsWith(".com")
    return if (windows && !direct) listOf("cmd.exe", "/c") + command else command
}
