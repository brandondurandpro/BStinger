package org.example.kotlineditor.acp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.model.AgentOption
import java.io.IOException

private const val NEW_SESSION = "A new session of the agent started: it no longer remembers the previous messages."
private const val NO_HISTORY = "The agent does not replay its sessions: the conversation goes on from here."

// The error ACP agents answer when the user must log in first.
private const val AUTH_REQUIRED = -32_000

/** The agent needs the user to log in before it starts a session. */
internal class LoginRequiredException(message: String) : IOException(message)

/** The settings a new session of the agent would have with some choices made; null when it cannot say. */
internal fun interface Preview {
    suspend fun optionsWith(choices: Map<String, String>): List<AgentOption>?
}

/** A session of an agent process, and the process it belongs to. */
internal class AgentSession(val process: AgentProcess, val id: String, val directory: String) {
    /** Whether it can take a prompt to work in [place]. */
    fun serves(place: String): Boolean = process.isAlive && directory == place
}

/**
 * The session of the agent of one conversation: the one it runs, or the one it will reopen (a session of the agent
 * opened from the history, the session of a conversation the server forgot when it restarted, the session of an agent
 * that stopped). The agent keeps the sessions and their history; a session it cannot reopen is replaced by a new one,
 * with a notice.
 */
internal class SessionKeeper(
    private val journal: Journal,
    private val writer: TranscriptWriter,
    private val listener: SessionListener,
) {
    @Volatile
    var current: AgentSession? = null
        private set

    // The session to reopen before the next prompt, and whether its history must replay.
    @Volatile
    private var resumable: String? = null

    @Volatile
    private var replay = false
    private var hadSession = false

    // The settings the user chose, in order, set in every session it starts (the first one, or one that replaces a
    // session lost); the lock keeps them in order.
    private val choices = LinkedHashMap<String, String>()
    private val settings = Mutex()

    /**
     * Reopens [session] before the next prompt, unless a session runs already; its history replays into the journal
     * when [replay] (a session opened from the history), not when the IDE shows it already (after a restart).
     */
    fun resume(session: String, replay: Boolean) {
        if (current != null) return
        resumable = session
        this.replay = replay
    }

    /** The agent of the session stopped: the next prompt reopens its session in a new process, when it can. */
    fun processDied() {
        val dead = current ?: return
        resumable = resumable ?: dead.id
        replay = false
        current = null
    }

    /**
     * The session to prompt in [directory]: the current one, the one to reopen, or a new one, with its process (started
     * when needed). Its exceptions say why it could not start.
     */
    suspend fun sessionFor(directory: String, process: () -> AgentProcess): AgentSession {
        val running = current
        if (running != null && running.serves(directory)) return running
        running?.process?.release(running.id)
        val agent = process()
        val info = agent.ready()
        journal.describe { it.copy(agentName = info.name) }
        val previous = resumable ?: running?.id?.takeIf { running.directory == directory }
        val reopened = previous?.let { reopen(agent, it, directory) }
        val forgot = hadSession || previous != null
        if (reopened == null && forgot) writer.notice(NEW_SESSION)
        val session = started(reopened ?: fresh(agent, info, directory))
        // The agent may list its commands before the session is listened to: those it last listed meanwhile.
        val known = agent.commands
        if (known.isNotEmpty()) journal.describe { it.copy(commands = it.commands.ifEmpty { known }) }
        settings.withLock { applyChoices(session) }
        return session
    }

    /**
     * Sets [option] to [value]: in the running session, or in the next one. Meanwhile the settings shown are those
     * [preview] says a session with the choices made has (a model changes the efforts it offers), else [known] (those
     * the agent offers) with the choice made.
     */
    suspend fun setOption(option: String, value: String, known: List<AgentOption>, preview: Preview) {
        settings.withLock {
            choices.remove(option)
            choices[option] = value
            val running = current?.takeIf { it.process.isAlive }
            if (running == null) {
                val previewed = preview.optionsWith(choices.toMap())
                journal.describe { it.copy(options = previewed ?: chosen(it.options.ifEmpty { known }, option, value)) }
            } else {
                val options = running.process.setOption(running.id, option, value)
                journal.describe { it.copy(options = options ?: chosen(it.options, option, value)) }
            }
        }
    }

    // Sets the choices of the user in [session]; one the agent refuses becomes a notice, and is forgotten.
    private suspend fun applyChoices(session: AgentSession) {
        for ((option, value) in choices.entries.toList()) {
            try {
                val options = session.process.setOption(session.id, option, value)
                journal.describe { it.copy(options = options ?: chosen(it.options, option, value)) }
            } catch (refused: LspResponseException) {
                choices.remove(option)
                writer.notice("The agent refused $value for $option: ${refused.message}", error = true)
            }
        }
    }

    // A new session of [agent]: its exceptions say why it could not start.
    private suspend fun fresh(agent: AgentProcess, info: AgentInfo, directory: String): AgentSession {
        val started =
            try {
                agent.newSession(directory, listener)
            } catch (refused: LspResponseException) {
                throw if (refused.code == AUTH_REQUIRED) loginRequired(refused, info) else refused
            }
        writer.newSession()
        started.options?.let { options -> journal.describe { it.copy(options = options) } }
        return AgentSession(agent, started.id, directory)
    }

    /** Ends the session in the agent; the agent keeps its history. */
    suspend fun close() {
        val running = current ?: return
        current = null
        running.process.closeSession(running.id)
    }

    private fun started(session: AgentSession): AgentSession {
        resumable = null
        hadSession = true
        current = session
        journal.describe { it.copy(session = session.id) }
        return session
    }

    // Reopens [id]: the history replays into an empty journal (and replaces what the IDE shows), and is ignored when
    // the journal shows it already. Null when the agent cannot reopen it.
    private suspend fun reopen(agent: AgentProcess, id: String, directory: String): AgentSession? {
        val wanted = replay && journal.last() == null
        val method = reopenMethod(agent.ready().sessions, wanted) ?: return null
        val replays = method == LOAD
        writer.newSession()
        writer.mode =
            when {
                !replays -> Mode.Live
                wanted -> Mode.Replay
                else -> Mode.Muted
            }
        if (replays && wanted) journal.describe { it.copy(replayed = true) }
        val reopened =
            try {
                val options = agent.reopenSession(id, directory, listener, method)
                options?.let { listed -> journal.describe { it.copy(options = listed) } }
                true
            } catch (_: LspResponseException) {
                // It forgot the session: a new one starts, with a notice.
                false
            } finally {
                writer.mode = Mode.Live
            }
        val notReplayed = wanted && !replays
        if (reopened && notReplayed) writer.notice(NO_HISTORY)
        return AgentSession(agent, id, directory).takeIf { reopened }
    }
}

private fun loginRequired(refused: LspResponseException, info: AgentInfo): IOException {
    val methods = info.authMethods.joinToString("\n") { "- $it" }
    val how = if (methods.isEmpty()) "" else "\n$methods"
    return LoginRequiredException("Log in to the agent first (${refused.message}).$how")
}
