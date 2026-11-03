package org.example.kotlineditor.acp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// The error ACP agents answer when the user must log in first.
private const val AUTH_REQUIRED = -32_000

/** The agent needs the user to log in before it starts a session. */
private class LoginRequiredException(message: String) : IOException(message)

/** A session of an agent process, and the process it belongs to. */
private class AgentSession(val process: AgentProcess, val id: String, val directory: String)

/**
 * One conversation on the server: its [journal], the session of its agent, the prompt running and the permission
 * requests waiting for the user. The updates of the agent become items of the journal as they arrive.
 */
internal class ServerConversation(
    val journal: Journal,
    private val scope: CoroutineScope,
    private val settings: AgentSettings,
) : SessionListener {
    private val lock = Any()
    private var session: AgentSession? = null
    private var hadSession = false
    private var job: Job? = null

    @Volatile
    private var stopping = false
    private val permissions = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    // Journal item of each tool call of the current session, by the id the agent gave it.
    private val toolItems = ConcurrentHashMap<String, String>()

    /** Starts answering [text], shown as item [itemId]; a prompt sent again (same item) is ignored. */
    fun prompt(itemId: String, text: String, directory: String, process: () -> AgentProcess) {
        synchronized(lock) {
            if (itemId in journal) return
            check(job?.isActive != true) { "The agent is still answering" }
            journal.add { AgentItem.UserText(itemId, it, text) }
            stopping = false
            journal.setState(AgentTurnState.Starting)
            job = scope.launch { answer(text, directory, process) }
        }
    }

    /** Stops the prompt: the agent is asked to stop, and abandoned if it does not within the grace delay. */
    fun cancel() {
        val running = synchronized(lock) { job?.takeIf { it.isActive } } ?: return
        stopping = true
        session?.let { it.process.cancel(it.id) }
        cancelPermissions()
        scope.launch {
            delay(settings.cancelGraceMs)
            running.cancel()
        }
    }

    /** Answers the permission request [itemId] with [option]; a request already answered keeps its answer. */
    fun answer(itemId: String, option: String) {
        permissions.remove(itemId)?.complete(option)
    }

    fun close() {
        job?.cancel()
        cancelPermissions()
        session?.let { it.process.release(it.id) }
        session = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun answer(text: String, directory: String, process: () -> AgentProcess) {
        var stopReason: String? = null
        try {
            val current = sessionFor(directory, process)
            journal.setState(AgentTurnState.Running)
            stopReason = current.process.prompt(current.id, text)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (login: LoginRequiredException) {
            notice(login.message.orEmpty(), error = true)
        } catch (refused: LspResponseException) {
            notice(refusal(refused), error = true)
        } catch (failure: IOException) {
            val dead = session?.process?.takeUnless { it.isAlive }
            notice(dead?.exitReason() ?: (failure.message ?: "The agent failed"), error = true)
            if (dead != null) session = null
        } finally {
            finish(stopReason)
        }
    }

    private fun finish(stopReason: String?) {
        val ended = stopReason == "end_turn" && !stopping
        val status = if (ended) AgentToolStatus.Completed else AgentToolStatus.Failed
        closeRunningTools(status)
        cancelPermissions()
        when {
            stopping -> notice("Stopped.", error = false)
            stopReason == "max_tokens" -> notice("The agent stopped: it reached its token limit.", error = true)
            stopReason == "max_turn_requests" -> notice("The agent stopped: too many steps in one turn.", error = true)
            stopReason == "refusal" -> notice("The agent refused to go on.", error = true)
        }
        journal.setState(AgentTurnState.Idle)
    }

    // The session of the conversation, started (with its process) on first use or when the agent stopped since.
    private suspend fun sessionFor(directory: String, process: () -> AgentProcess): AgentSession {
        // Its exception says why; the caller turns it into a notice.
        val current = session
        if (current != null && current.process.isAlive && current.directory == directory) return current
        current?.process?.release(current.id)
        if (hadSession) notice("A new session of the agent started: it no longer remembers the previous messages.")
        val agent = process()
        val info = agent.ready()
        journal.setAgentName(info.name)
        val id =
            try {
                agent.newSession(directory, this)
            } catch (refused: LspResponseException) {
                throw if (refused.code == AUTH_REQUIRED) loginRequired(refused, info) else refused
            }
        toolItems.clear()
        hadSession = true
        return AgentSession(agent, id, directory).also { session = it }
    }

    override fun onUpdate(update: JsonObject) {
        when (update.text("sessionUpdate")) {
            "agent_message_chunk" -> append(update, thought = false)
            "agent_thought_chunk" -> append(update, thought = true)
            "tool_call", "tool_call_update" -> tool(update)
            "plan" -> plan(update)
        }
    }

    override suspend fun requestPermission(params: JsonObject): String? {
        val call = params["toolCall"].obj()
        call?.let(::tool)
        val title = call?.text("title") ?: call?.text("toolCallId")?.let { toolTitle(it) } ?: "Run a tool"
        val options = params.permissionOptions()
        val answer = CompletableDeferred<String?>()
        val itemId = journal.newId()
        permissions[itemId] = answer
        journal.add { AgentItem.Permission(itemId, it, title, options) }
        if (stopping) cancelPermissions()
        val chosen = answer.await()
        journal.update(itemId) { item, version ->
            (item as AgentItem.Permission).copy(version = version, chosen = chosen, cancelled = chosen == null)
        }
        return chosen
    }

    // A chunk of text: it grows the text item it continues, or starts one.
    private fun append(update: JsonObject, thought: Boolean) {
        val chunk = update["content"].obj()?.contentText().orEmpty()
        if (chunk.isEmpty()) return
        val last = journal.last()
        val continued = if (thought) last is AgentItem.Thought else last is AgentItem.AgentText
        if (last != null && continued) {
            journal.update(last.id) { item, version -> appended(item, chunk, version) }
            return
        }
        val id = journal.newId()
        journal.add { version ->
            if (thought) AgentItem.Thought(id, version, chunk) else AgentItem.AgentText(id, version, chunk)
        }
    }

    private fun appended(item: AgentItem, chunk: String, version: Long): AgentItem =
        when (item) {
            is AgentItem.Thought -> item.copy(version = version, text = item.text + chunk)
            is AgentItem.AgentText -> item.copy(version = version, text = item.text + chunk)
            else -> item
        }

    // A tool call starts or changes: the fields present replace those of the item.
    private fun tool(update: JsonObject) {
        val callId = update.text("toolCallId") ?: return
        val content = update.toolContent()
        val locations = update.toolLocations()
        val existing = toolItems[callId]
        if (existing == null) {
            val id = journal.newId()
            toolItems[callId] = id
            journal.add { version ->
                AgentItem.ToolCall(
                    id = id,
                    version = version,
                    title = update.text("title") ?: callId,
                    kind = toolKind(update.text("kind")),
                    status = toolStatus(update.text("status")) ?: AgentToolStatus.Pending,
                    output = content?.output.orEmpty(),
                    diffs = content?.diffs.orEmpty(),
                    locations = locations.orEmpty(),
                )
            }
            return
        }
        journal.update(existing) { item, version ->
            val call = item as AgentItem.ToolCall
            call.copy(
                version = version,
                title = update.text("title") ?: call.title,
                kind = update.text("kind")?.let(::toolKind) ?: call.kind,
                status = toolStatus(update.text("status")) ?: call.status,
                output = content?.output ?: call.output,
                diffs = content?.diffs ?: call.diffs,
                locations = locations ?: call.locations,
            )
        }
    }

    private fun toolTitle(callId: String): String? =
        toolItems[callId]?.let { journal[it] as? AgentItem.ToolCall }?.title

    // The plan of the prompt: the latest plan item is replaced while it is the last plan of the prompt.
    private fun plan(update: JsonObject) {
        val entries = update.planEntries()
        val promptStart = journal.filter { it as? AgentItem.UserText }.lastOrNull()?.version ?: 0L
        val current = journal.filter { it as? AgentItem.Plan }.lastOrNull()
        val replaced = current != null && current.version > promptStart
        if (current != null && replaced) {
            journal.update(current.id) { item, version -> (item as AgentItem.Plan).copy(version = version, entries = entries) }
            return
        }
        val id = journal.newId()
        journal.add { AgentItem.Plan(id, it, entries) }
    }

    private fun closeRunningTools(status: AgentToolStatus) {
        val running = journal.filter { item -> (item as? AgentItem.ToolCall)?.takeIf { it.isRunning() } }
        for (call in running) {
            journal.update(call.id) { item, version -> (item as AgentItem.ToolCall).copy(version = version, status = status) }
        }
    }

    private fun AgentItem.ToolCall.isRunning() = status == AgentToolStatus.Pending || status == AgentToolStatus.Running

    private fun cancelPermissions() {
        val waiting = permissions.keys.toList()
        waiting.forEach { permissions.remove(it)?.complete(null) }
    }

    private fun notice(text: String, error: Boolean = false) {
        val id = journal.newId()
        journal.add { AgentItem.Notice(id, it, text, error) }
    }

    private fun refusal(refused: LspResponseException): String {
        val message = refused.message?.takeIf { it.isNotBlank() } ?: "The agent refused the request"
        return "The agent failed: $message"
    }

    private fun loginRequired(refused: LspResponseException, info: AgentInfo): IOException {
        val methods = info.authMethods.joinToString("\n") { "- $it" }
        val how = if (methods.isEmpty()) "" else "\n$methods"
        return LoginRequiredException("Log in to the agent first (${refused.message}).$how")
    }
}
