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
import org.example.kotlineditor.model.AgentPermissionOption
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

private const val NEW_SESSION = "A new session of the agent started: it no longer remembers the previous messages."

// The error ACP agents answer when the user must log in first.
private const val AUTH_REQUIRED = -32_000

/** The agent needs the user to log in before it starts a session. */
private class LoginRequiredException(message: String) : IOException(message)

/** A session of an agent process, and the process it belongs to. */
private class AgentSession(val process: AgentProcess, val id: String, val directory: String) {
    /** Whether it can take a prompt to work in [place]. */
    fun serves(place: String): Boolean = process.isAlive && directory == place
}

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
    private val writer = TranscriptWriter(journal)
    private var session: AgentSession? = null
    private var hadSession = false
    private var job: Job? = null

    @Volatile
    private var stopping = false
    private val permissions = ConcurrentHashMap<String, CompletableDeferred<String?>>()

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

    /** Stops the prompt and ends the session. */
    fun close() {
        job?.cancel()
        cancelPermissions()
        session?.let { it.process.release(it.id) }
        session = null
    }

    override fun onUpdate(update: JsonObject) = writer.write(update)

    override suspend fun requestPermission(params: JsonObject): String? {
        val title = writer.permissionTitle(params["toolCall"].obj())
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
            writer.notice(login.message.orEmpty(), error = true)
        } catch (refused: LspResponseException) {
            writer.notice(refusal(refused), error = true)
        } catch (failure: IOException) {
            val dead = session?.process?.takeUnless { it.isAlive }
            writer.notice(dead?.exitReason() ?: (failure.message ?: "The agent failed"), error = true)
            if (dead != null) session = null
        } finally {
            finish(stopReason)
        }
    }

    private fun finish(stopReason: String?) {
        val ended = stopReason == "end_turn" && !stopping
        writer.closeRunningTools(if (ended) AgentToolStatus.Completed else AgentToolStatus.Failed)
        cancelPermissions()
        when {
            stopping -> writer.notice("Stopped.")
            stopReason == "max_tokens" -> writer.notice("The agent stopped: it reached its token limit.", error = true)
            stopReason == "max_turn_requests" -> writer.notice("The agent stopped: too many steps in one turn.", true)
            stopReason == "refusal" -> writer.notice("The agent refused to go on.", error = true)
        }
        journal.setState(AgentTurnState.Idle)
    }

    // The session of the conversation, started (with its process) on first use or when the agent stopped since. Its
    // exceptions say why it could not start; the caller turns them into a notice.
    private suspend fun sessionFor(directory: String, process: () -> AgentProcess): AgentSession {
        val current = session
        if (current != null && current.serves(directory)) return current
        current?.process?.release(current.id)
        if (hadSession) writer.notice(NEW_SESSION)
        val agent = process()
        val info = agent.ready()
        journal.setAgentName(info.name)
        val id =
            try {
                agent.newSession(directory, this)
            } catch (refused: LspResponseException) {
                throw if (refused.code == AUTH_REQUIRED) loginRequired(refused, info) else refused
            }
        writer.newSession()
        hadSession = true
        return AgentSession(agent, id, directory).also { session = it }
    }

    private fun cancelPermissions() {
        val waiting = permissions.keys.toList()
        waiting.forEach { permissions.remove(it)?.complete(null) }
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

private fun loginRequired(refused: LspResponseException, info: AgentInfo): IOException {
    val methods = info.authMethods.joinToString("\n") { "- $it" }
    val how = if (methods.isEmpty()) "" else "\n$methods"
    return LoginRequiredException("Log in to the agent first (${refused.message}).$how")
}
