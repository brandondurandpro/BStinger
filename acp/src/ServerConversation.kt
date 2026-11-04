package org.example.kotlineditor.acp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.model.AgentAttachment
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentPermissionOption
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
    private val writer: TranscriptWriter = TranscriptWriter(journal) { id -> keeper.terminalOf(id) }

    /** The session of its agent; its settings (the model…) are set there. */
    val keeper: SessionKeeper = SessionKeeper(journal, writer, this)
    private val prompted = HashSet<String>()
    private var opened = false
    private var job: Job? = null

    // Whether a prompt or an opening is under way: false once the turn is idle, before its coroutine returns, so that
    // a prompt sent as soon as the previous one shows idle is not refused.
    @Volatile
    private var busy = false

    @Volatile
    private var stopping = false
    private val permissions = Permissions(journal, writer)

    /**
     * Starts answering [text], shown as item [itemId], in the agent's [session] when the conversation has none yet. A
     * prompt sent again (same item) is ignored.
     */
    fun prompt(prompt: UserPrompt, directory: String, session: String?, process: () -> AgentProcess) {
        val itemId = prompt.itemId
        synchronized(lock) {
            if (!prompted.add(itemId)) return
            check(!busy) { "The agent is still answering" }
            session?.let { keeper.resume(it, replay = false) }
            stopping = false
            busy = true
            journal.setState(AgentTurnState.Starting)
            job = scope.launch { run(prompt, directory, process) }
        }
    }

    /** Opens the agent's [session], whose history replays into the journal; opening it again does nothing. */
    fun open(session: String, directory: String, process: () -> AgentProcess) {
        synchronized(lock) {
            if (opened || busy) return
            opened = true
            keeper.resume(session, replay = true)
            busy = true
            journal.setState(AgentTurnState.Starting)
            job =
                scope.launch {
                    try {
                        failures(writer, keeper, before = {}) { keeper.sessionFor(directory, process) }
                    } finally {
                        busy = false
                        journal.setState(AgentTurnState.Idle)
                    }
                }
        }
    }

    /** Stops the prompt: the agent is asked to stop, and abandoned if it does not within the grace delay. */
    fun cancel() {
        val running = synchronized(lock) { job?.takeIf { it.isActive } } ?: return
        stopping = true
        keeper.current?.let { it.process.cancel(it.id) }
        permissions.cancelAll()
        scope.launch {
            delay(settings.cancelGraceMs)
            running.cancel()
        }
    }

    /** Answers the permission request [itemId] with [option]; a request already answered keeps its answer. */
    fun answer(itemId: String, option: String) {
        permissions.answer(itemId, option)
    }

    /** Stops the prompt and ends the session; the agent keeps its history. */
    suspend fun close() {
        job?.cancel()
        permissions.cancelAll()
        keeper.close()
    }

    override fun onUpdate(update: JsonObject) = writer.write(update)

    override fun directory(): String? = keeper.current?.directory

    override fun onTerminal(terminal: AgentTerminal) = writer.terminalChanged(terminal)

    override suspend fun requestPermission(params: JsonObject): String? = permissions.ask(params, cancelled = stopping)

    private suspend fun run(prompt: UserPrompt, directory: String, process: () -> AgentProcess) {
        var stopReason: String? = null
        val context = prompt.attachments.map { it.label }
        // After the history the agent may replay, before what goes wrong.
        val shown = {
            if (prompt.itemId !in journal) journal.add { AgentItem.UserText(prompt.itemId, it, prompt.text, context) }
        }
        try {
            failures(writer, keeper, shown) {
                val current = keeper.sessionFor(directory, process)
                shown()
                journal.setState(AgentTurnState.Running)
                val take = current.process.ready().prompt
                val content =
                    withContext(Dispatchers.IO) { promptContent(prompt.text, prompt.attachments, directory, take) }
                content.leftOut.forEach { writer.notice(it, error = true) }
                stopReason = current.process.prompt(current.id, content.blocks)
            }
        } finally {
            shown()
            finish(stopReason)
        }
    }

    private fun finish(stopReason: String?) {
        val ended = stopReason == "end_turn" && !stopping
        writer.closeRunningTools(if (ended) AgentToolStatus.Completed else AgentToolStatus.Failed)
        permissions.cancelAll()
        when {
            stopping -> writer.notice("Stopped.")
            stopReason == "max_tokens" -> writer.notice("The agent stopped: it reached its token limit.", error = true)
            stopReason == "max_turn_requests" -> writer.notice("The agent stopped: too many steps in one turn.", true)
            stopReason == "refusal" -> writer.notice("The agent refused to go on.", error = true)
        }
        busy = false
        journal.setState(AgentTurnState.Idle)
    }
}

/** A prompt of the user: [text], shown as item [itemId], and what the user joined to it. */
internal class UserPrompt(val itemId: String, val text: String, val attachments: List<AgentAttachment> = emptyList())

/** The permission requests of a conversation waiting for the user, each one an item of its journal. */
private class Permissions(private val journal: Journal, private val writer: TranscriptWriter) {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    /** Asks the user, and waits for the answer: the option chosen, null when [cancelled] or cancelled meanwhile. */
    suspend fun ask(params: JsonObject, cancelled: Boolean): String? {
        val title = writer.permissionTitle(params["toolCall"].obj())
        val options = params.permissionOptions()
        val answer = CompletableDeferred<String?>()
        val itemId = journal.newId()
        waiting[itemId] = answer
        journal.add { AgentItem.Permission(itemId, it, title, options) }
        if (cancelled) cancelAll()
        val chosen = answer.await()
        journal.update(itemId) { item, version ->
            (item as AgentItem.Permission).copy(version = version, chosen = chosen, cancelled = chosen == null)
        }
        return chosen
    }

    /** Answers [itemId] with [option]; a request already answered keeps its answer. */
    fun answer(itemId: String, option: String) {
        waiting.remove(itemId)?.complete(option)
    }

    /** Cancels every request still waiting. */
    fun cancelAll() {
        waiting.keys.toList().forEach { waiting.remove(it)?.complete(null) }
    }
}

private fun JsonObject.permissionOptions(): List<AgentPermissionOption> =
    get("options").array().mapNotNull { it.obj()?.permissionOption() }

private fun JsonObject.permissionOption(): AgentPermissionOption? {
    val id = text("optionId") ?: return null
    return AgentPermissionOption(id, text("name") ?: id, text("kind").orEmpty())
}

private fun refusal(refused: LspResponseException): String {
    val message = refused.message?.takeIf { it.isNotBlank() } ?: "The agent refused the request"
    return "The agent failed: $message"
}

// Runs [work]; its failures become notices, written after what [before] writes.
private suspend fun failures(
    writer: TranscriptWriter,
    keeper: SessionKeeper,
    before: () -> Unit,
    work: suspend () -> Unit,
) {
    try {
        work()
    } catch (login: LoginRequiredException) {
        before()
        writer.notice(login.message.orEmpty(), error = true)
    } catch (refused: LspResponseException) {
        before()
        writer.notice(refusal(refused), error = true)
    } catch (failure: IOException) {
        before()
        val dead = keeper.current?.process?.takeUnless { it.isAlive }
        writer.notice(dead?.exitReason() ?: (failure.message ?: "The agent failed"), error = true)
        if (dead != null) keeper.processDied()
    }
}

// The terminal [id] of the agent process of [keeper].
private fun SessionKeeper.terminalOf(id: String): AgentTerminal? {
    val process = current?.process ?: return null
    return process.terminals[id]
}
