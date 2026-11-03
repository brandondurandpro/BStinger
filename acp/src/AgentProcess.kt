package org.example.kotlineditor.acp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.lsp.jsonrpc.LineFraming
import org.example.kotlineditor.lsp.jsonrpc.LspConnection
import org.example.kotlineditor.lsp.supervisor.LogRing
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentSlashCommand
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

private const val EXIT_WAIT_MS = 1_000L
private const val STDERR_LINES = 200

/** What a session receives from its agent: its updates and its permission requests. */
internal interface SessionListener {
    /** A `session/update` of the session: a chunk of text, a tool call, a plan. */
    fun onUpdate(update: JsonObject)

    /** Asks the user; returns the option chosen, null when the prompt was cancelled first. */
    suspend fun requestPermission(params: JsonObject): String?

    /** Directory the session works in, where its commands run by default; null before it started. */
    fun directory(): String?

    /** The output of a command of the session changed, or the command exited. */
    fun onTerminal(terminal: AgentTerminal)
}

/**
 * One agent process and its ACP connection, shared by the sessions of every conversation that uses the same
 * [command]. It is the client side of ACP: [AgentRequests] answers what the agent asks (files, permissions), and the
 * updates go to the session they belong to.
 */
internal class AgentProcess(val command: AgentCommand, scope: CoroutineScope, files: FileAccess) {
    private val log = LogRing(STDERR_LINES)
    private val sessions = ConcurrentHashMap<String, SessionListener>()

    /** The commands the agent runs on the server. */
    val terminals = AgentTerminals(scope)
    private val requests = AgentRequests(sessions, files, terminals)
    private val start = Mutex()
    private var info: AgentInfo? = null

    /** The settings (model…) the latest new session offered; null before one started. */
    @Volatile
    var offered: List<AgentOption>? = null

    /** The commands the agent offered in its latest session; empty before it said. */
    @Volatile
    var commands: List<AgentSlashCommand> = emptyList()

    /** Directory the latest new session works in; null before one started. */
    @Volatile
    var lastDirectory: String? = null

    private val process: Process =
        try {
            ProcessBuilder(commandLine(command.command))
                .directory(File(System.getProperty("user.home")))
                .apply { environment().putAll(command.environment) }
                .start()
        } catch (failure: IOException) {
            val line = command.command.joinToString(" ")
            throw IOException("Cannot start the agent $line: ${failure.message}", failure)
        }

    private val connection =
        LspConnection(
            process.inputStream,
            process.outputStream,
            scope,
            onRequest = requests::answer,
            onNotification = ::onNotification,
            framing = LineFraming,
            cancelMethod = "$/cancel_request",
            peer = "The agent connection",
        )

    /** Whether the process still answers. */
    val isAlive: Boolean get() = !connection.isClosed && process.isAlive

    /** Whether no session uses the process any more. */
    val unused: Boolean get() = sessions.isEmpty()

    init {
        thread(name = "agent-stderr", isDaemon = true) {
            runCatching { process.errorStream.bufferedReader().forEachLine(log::add) }
        }
        connection.start()
    }

    /** Why the agent stopped: its exit code and the last line it wrote on stderr. */
    fun exitReason(): String {
        val exited = process.waitFor(EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
        val code = if (exited) " (exit code ${process.exitValue()})" else ""
        val last = log.last()?.let { ": $it" }.orEmpty()
        return "The agent stopped$code$last"
    }

    /** Initializes the connection on first use, and returns what the agent said about itself. */
    suspend fun ready(): AgentInfo =
        start.withLock {
            info ?: agentInfo(connection.request("initialize", initializeParams()).obj()).also { info = it }
        }

    /** Sends the request [method] to the agent and returns its answer, an object (null for anything else). */
    suspend fun request(method: String, params: JsonObject): JsonObject? = connection.request(method, params).obj()

    /** Sends the updates and the permission requests of [session] to [listener] from now on. */
    fun listen(session: String, listener: SessionListener) {
        sessions[session] = listener
    }

    /** Asks the agent to stop the turn of [session]; it answers the prompt with `cancelled`. */
    fun cancel(session: String) {
        runCatching { connection.notify("session/cancel", buildJsonObject { put(SESSION_ID, session) }) }
    }

    /** Forgets [session]: its updates are ignored from now on. */
    fun release(session: String) {
        sessions.remove(session)
        terminals.releaseSession(session)
    }

    /** Stops the process and the programs it started (npx starts node). */
    fun destroy() {
        connection.close()
        terminals.close()
        process.descendants().forEach { it.destroy() }
        process.destroy()
    }

    // A session/update goes to its session; other notifications are ignored.
    private fun onNotification(method: String, params: JsonElement?) {
        val update = params.obj()?.takeIf { method == "session/update" }
        val session = update?.text(SESSION_ID)?.let(sessions::get)
        val content = update?.get("update").obj()
        // Kept for the conversations that have no session yet, even when the session is not listened to (yet).
        content?.slashCommands()?.let { commands = it }
        if (session != null && content != null) session.onUpdate(content)
    }
}

/** [command] as the system starts it: Windows starts `npx` (a .cmd script) only through cmd.exe, an .exe directly. */
internal fun commandLine(command: List<String>): List<String> {
    val windows = System.getProperty("os.name").startsWith("Windows")
    val program = command.firstOrNull()?.lowercase().orEmpty()
    val direct = program.endsWith(".exe") || program.endsWith(".com")
    return if (windows && !direct) listOf("cmd.exe", "/c") + command else command
}
