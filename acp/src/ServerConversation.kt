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
import org.example.kotlineditor.model.AGENT_BUSY_MESSAGE
import org.example.kotlineditor.model.AgentAttachment
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentPermissionOption
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

// How many prompts a conversation keeps while its agent answers; beyond it the server refuses, and the IDE sends
// the prompt again once the turn is over.
private const val QUEUED_PROMPTS = 4

// How many finished prompts keep their outcome (with the full answer): enough for a holder that awaits the turns it
// started; the older ones are forgotten, so that a long conversation does not keep every answer twice.
private const val KEPT_OUTCOMES = 32

private const val CLOSED = "The conversation was closed."

private const val UNANSWERED = "The agent stopped without answering."

/** The kind of the options of a question to the user: choices, which none of the permission kinds names. */
const val QUESTION_CHOICE = "question"

/**
 * One conversation on the server: its [journal], the session of its agent, the prompt running and the permission
 * requests waiting for the user. The updates of the agent become items of the journal as they arrive.
 */
@Suppress("TooManyFunctions") // the whole agent conversation in one lifecycle: prompt, resume, cancel, answer…
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

    @Volatile
    private var closed = false

    // The leases held (lock held to change them), and the outcome of each prompt accepted, by item id.
    private val leases = ArrayList<ConversationLease>()
    private val outcomes = ConcurrentHashMap<String, CompletableDeferred<TurnOutcome>>()

    // The prompts whose outcome is known, oldest first (lock held to change it): beyond [KEPT_OUTCOMES] the oldest
    // outcome is dropped. A prompt still running or waiting always keeps its own.
    private val finished = ArrayDeque<String>()

    // The prompts that arrived while the agent answered: run in turn, each once the one before it ended. Bounded
    // by [QUEUED_PROMPTS]: beyond it the caller is refused, and the IDE sends its prompt again once the turn is over.
    private val queued = ArrayDeque<Queued>()
    private val permissions = Permissions(journal, writer)

    /**
     * Starts answering [text], shown as item [itemId], by [agent] — in its [session] when the conversation has none
     * yet, in a new session of [agent] when it runs another one. A prompt sent again (same item) is ignored. A prompt
     * of the holder of a [lease] does not wait for the other prompts queued during the lease.
     */
    fun prompt(
        prompt: UserPrompt,
        agent: AgentCommand,
        directory: String,
        session: String?,
        process: () -> AgentProcess,
        lease: ConversationLease? = null,
    ) {
        val itemId = prompt.itemId
        require(lease == null || lease.belongsTo(this)) { "The lease is of another conversation" }
        synchronized(lock) {
            // A prompt sent again (the same item) is ignored, whether it runs, waits or has run.
            if (itemId in prompted) return
            if (mustWait(lease)) {
                if (queued.size >= QUEUED_PROMPTS) check(false) { AGENT_BUSY_MESSAGE }
                prompted.add(itemId)
                outcomes[itemId] = CompletableDeferred()
                queued.addLast(Queued(prompt, agent, session, directory, process))
                return
            }
            prompted.add(itemId)
            outcomes[itemId] = CompletableDeferred()
            start(prompt, agent, directory, session, process)
        }
    }

    // Lock held. Whether a prompt of [lease] (null: of anyone else) goes to the queue: a turn runs, or, for the others,
    // a lease is held or prompts already wait.
    private fun mustWait(lease: ConversationLease?): Boolean {
        if (busy) return true
        val others = leases.isNotEmpty() || queued.isNotEmpty()
        return others && lease == null
    }

    /** The outcome of the turn the prompt [itemId] started, once it ended; fails for a prompt this one never got. */
    suspend fun awaitTurn(itemId: String): TurnOutcome {
        val outcome = outcomes[itemId]
        requireNotNull(outcome) { "No prompt $itemId in this conversation, or its outcome is no longer kept" }
        return outcome.await()
    }

    /** Keeps the conversation busy until the lease is released. */
    fun keepBusy(): ConversationLease {
        val lease = ConversationLease(this)
        synchronized(lock) {
            leases.add(lease)
            if (!busy) journal.setState(AgentTurnState.Running)
        }
        return lease
    }

    /** Ends [lease]: the conversation shows idle again when nothing else runs, and the prompts that waited start. */
    fun release(lease: ConversationLease) {
        synchronized(lock) {
            if (!leases.remove(lease)) return
            if (leases.isNotEmpty() || busy) return
            journal.setState(AgentTurnState.Idle)
            startQueued()
        }
    }

    /** Opens the agent's [session], whose history replays into the journal; opening it again does nothing. */
    fun open(agent: AgentCommand, session: String, directory: String, process: () -> AgentProcess) {
        synchronized(lock) {
            if (opened || busy) return
            opened = true
            keeper.resume(agent, session, replay = true)
            busy = true
            journal.setState(AgentTurnState.Starting)
            job =
                scope.launch {
                    try {
                        failures(writer, keeper, before = {}) { keeper.sessionFor(agent, directory, process) }
                    } finally {
                        busy = false
                        journal.setState(AgentTurnState.Idle)
                    }
                }
        }
    }

    /** Stops the prompt: the agent is asked to stop, and abandoned if it does not within the grace delay. */
    fun cancel() {
        val holders = synchronized(lock) { leases.toList() }
        holders.forEach { it.stop(StopCause.Cancelled) }
        // A question the user was asked between two turns of the agent waits with no prompt running.
        permissions.cancelAll()
        val running = synchronized(lock) { job?.takeIf { it.isActive } } ?: return
        stopping = true
        keeper.current?.let { it.process.cancel(it.id) }
        scope.launch {
            delay(settings.cancelGraceMs)
            running.cancel()
        }
    }

    /** Answers the permission request [itemId] with [option]; a request already answered keeps its answer. */
    fun answer(itemId: String, option: String) {
        permissions.answer(itemId, option)
    }

    /** Asks the user [question] in this conversation and waits for the answer; null when cancelled. */
    suspend fun ask(question: String, choices: List<String>): String? = permissions.ask(question, choices)

    /** Stops the prompt and ends the session; the agent keeps its history. */
    suspend fun close() {
        closed = true
        val holders =
            synchronized(lock) {
                job?.cancel()
                queued.clear()
                leases.toList().also { leases.clear() }
            }
        holders.forEach { it.stop(StopCause.Closed) }
        val forgotten = TurnOutcome(TurnEnding.Cancelled, "", CLOSED)
        outcomes.values.forEach { it.complete(forgotten) }
        permissions.cancelAll()
        keeper.close()
    }

    override fun onUpdate(update: JsonObject) = writer.write(update)

    override fun directory(): String? = keeper.current?.directory

    override fun onTerminal(terminal: AgentTerminal) = writer.terminalChanged(terminal)

    override suspend fun requestPermission(params: JsonObject): String? = permissions.ask(params, cancelled = stopping)

    private suspend fun run(prompt: UserPrompt, agent: AgentCommand, directory: String, process: () -> AgentProcess) {
        var stopReason: String? = null
        val context = prompt.attachments.map { it.label }
        val shownText = prompt.shown
        writer.turn.begin()
        // After the history the agent may replay, before what goes wrong; a hidden prompt shows nothing.
        val shown = {
            if (shownText != null && prompt.itemId !in journal) {
                journal.add { AgentItem.UserText(prompt.itemId, it, shownText, context) }
            }
        }
        try {
            failures(writer, keeper, shown) {
                val current = keeper.sessionFor(agent, directory, process)
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
            finish(prompt.itemId, stopReason)
        }
    }

    // How the turn ended, from the notices it wrote.
    private fun outcomeOf(stopReason: String?): TurnOutcome {
        val cancelled = closed || stopping || stopReason == "cancelled"
        val ending =
            when {
                cancelled -> TurnEnding.Cancelled
                stopReason == "end_turn" -> TurnEnding.Completed
                else -> TurnEnding.Failed
            }
        val reason = if (ending == TurnEnding.Failed) writer.turn.lastError ?: UNANSWERED else ""
        return TurnOutcome(ending, writer.turn.answer(), reason)
    }

    private fun finish(itemId: String, stopReason: String?) {
        val ended = stopReason == "end_turn" && !stopping
        writer.closeRunningTools(if (ended) AgentToolStatus.Completed else AgentToolStatus.Failed)
        permissions.cancelAll()
        when {
            stopping -> writer.notice("Stopped.")
            stopReason == "max_tokens" -> writer.notice("The agent stopped: it reached its token limit.", error = true)
            stopReason == "max_turn_requests" -> writer.notice("The agent stopped: too many steps in one turn.", true)
            stopReason == "refusal" -> writer.notice("The agent refused to go on.", error = true)
        }
        val outcome = outcomeOf(stopReason)
        synchronized(lock) {
            busy = false
            journal.setState(if (leases.isEmpty()) AgentTurnState.Idle else AgentTurnState.Running)
            startQueued()
        }
        // After the conversation is free: a holder that sends its next prompt as soon as it knows goes first.
        outcomes[itemId]?.complete(outcome)
        forgetOldOutcomes(itemId)
    }

    // [itemId] ended: it is the newest outcome kept, and the oldest ones beyond the limit are dropped.
    private fun forgetOldOutcomes(itemId: String) {
        synchronized(lock) {
            finished.addLast(itemId)
            while (finished.size > KEPT_OUTCOMES) {
                val oldest = finished.removeFirst()
                outcomes.remove(oldest)
            }
        }
    }

    // Lock held. Makes [prompt] the turn running: [agent] starts (or continues in [session]) and answers it.
    private fun start(
        prompt: UserPrompt,
        agent: AgentCommand,
        directory: String,
        session: String?,
        process: () -> AgentProcess,
    ) {
        session?.let { keeper.resume(agent, it, replay = false) }
        stopping = false
        busy = true
        journal.setState(AgentTurnState.Starting)
        job = scope.launch { run(prompt, agent, directory, process) }
    }

    // Lock held. Once the turn is over, the first prompt queued meanwhile goes.
    private fun startQueued() {
        if (leases.isNotEmpty()) return
        val next = queued.firstOrNull() ?: return
        queued.removeFirst()
        start(next.prompt, next.agent, next.directory, next.session, next.process)
    }
}

/** A prompt the server keeps while the agent answers, with the agent, where and how it runs once its turn comes. */
private class Queued(
    val prompt: UserPrompt,
    val agent: AgentCommand,
    val session: String?,
    val directory: String,
    val process: () -> AgentProcess,
)

/**
 * A prompt: [text] goes to the agent, [shown] (null: nothing) is what the transcript shows as item [itemId], and
 * [attachments] are what the user joined to it.
 */
internal class UserPrompt(
    val itemId: String,
    val text: String,
    val attachments: List<AgentAttachment> = emptyList(),
    val shown: String? = text,
)

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

    /**
     * Asks the user [question] with the [choices] proposed, and waits for the answer: the choice, or the words of the
     * user; null when it is cancelled meanwhile.
     */
    suspend fun ask(question: String, choices: List<String>): String? {
        val options = choices.map { AgentPermissionOption(it, it, QUESTION_CHOICE) }
        val answer = CompletableDeferred<String?>()
        val itemId = journal.newId()
        waiting[itemId] = answer
        journal.add { AgentItem.Permission(itemId, it, question, options, question = true) }
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
