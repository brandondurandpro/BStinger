package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentSlashCommand
import java.io.IOException

private const val COMMAND_TRIES = 5
private const val COMMAND_RETRY_MS = 400L

/**
 * The settings of the sessions of the conversations (the model…): the agent offers them, the user chooses one in the
 * conversation, the server sets it in the session (as soon as it starts, for a conversation without one yet). The
 * IDE keeps none of them: they are the session's, as the agent says. Each conversation runs its own agent — what
 * [agentCommand] says for it — so its settings are those this agent offers its session.
 */
/** The settings shown and chosen for the own agent of a conversation, put aside while a team answers it. */
internal class OwnSettings(val options: List<AgentOption>, val choices: Map<String, String>)

@Stable
class ConversationOptions internal constructor(
    private val project: AgentHost,
    private val agentCommand: (Conversation) -> AgentCommand,
) {
    private val agents get() = project.services.agents

    /** The commands the agent offered in its latest session: those of a conversation that has no session yet. */
    var commands: List<AgentSlashCommand> by mutableStateOf(emptyList())
        private set

    /**
     * Asks the server again for the commands of the agent while it knows none: an agent lists them a while after its
     * session started (Claude asks its SDK first), so a `/` typed early may come before them.
     */
    internal suspend fun refreshCommands(conversation: Conversation) {
        repeat(COMMAND_TRIES) { attempt ->
            if (commands.isNotEmpty()) return
            if (attempt > 0) delay(COMMAND_RETRY_MS)
            commands = knownCommands(conversation)
        }
    }

    /** Asks the server what a new session offers, for [conversation] while it shows no settings. */
    internal fun offer(conversation: Conversation) {
        val asking = conversation.offering?.isActive == true
        if (conversation.options.isNotEmpty() || asking) return
        conversation.offering =
            project.scope.launch {
                val offered = offeredTo(conversation, project.root.path)
                if (conversation.options.isEmpty()) conversation.options = offered
                // Known once a session of the agent started: the one that read its settings, at the latest. The
                // agent lists them a while after, and a `/` typed while it was starting gave up asking before.
                commands = knownCommands(conversation).ifEmpty { commands }
                refreshCommands(conversation)
            }
    }

    /**
     * Chooses [value] for the setting [option] of [conversation]: shown at once, set by the server in order; a value
     * the agent refuses comes back to the one before, with a notice.
     */
    fun choose(conversation: Conversation, option: String, value: String) {
        val before = conversation.options
        val known = before.firstOrNull { it.id == option }
        if (known == null || known.current == value) return
        conversation.options = before.map { if (it.id == option) it.copy(current = value) else it }
        conversation.choices[option] = value
        conversation.settingOptions += 1
        project.scope.launch {
            conversation.optionsLock.withLock {
                try {
                    agents.setOption(conversation.key, agentCommand(conversation), option, value)
                    project.followTranscript(conversation)
                } catch (failure: IOException) {
                    conversation.options = before
                    conversation.choices.remove(option)
                    conversation.note("The ${known.name.lowercase()} could not be changed: ${failure.message}")
                } finally {
                    conversation.settingOptions -= 1
                    conversation.showServerOptions()
                }
            }
        }
    }

    /**
     * Another agent answers [conversation] from now on: the manager of the team it just chose ([hasTeam]), or its own
     * agent again. The settings chosen for its own agent are put aside while a team answers (a team fixes its model
     * and effort) and come back with it; the server forgets the choices it was sent, which were for the agent before.
     */
    internal fun answererChanged(conversation: Conversation, hadTeam: Boolean, hasTeam: Boolean) {
        conversation.offering?.cancel()
        val sent = conversation.choices.isNotEmpty()
        if (!hadTeam) conversation.ownSettings = OwnSettings(conversation.options, conversation.choices.toMap())
        conversation.choices.clear()
        conversation.serverOptions = emptyList()
        val own = conversation.ownSettings
        if (hasTeam || own == null) {
            conversation.options = emptyList()
        } else {
            conversation.options = own.options
            conversation.choices.putAll(own.choices)
            conversation.ownSettings = null
        }
        if (sent) forgetChoices(conversation)
        offer(conversation)
    }

    // The server forgets what it was told of [conversation], which has not started: the choices of another agent.
    private fun forgetChoices(conversation: Conversation) {
        project.scope.launch {
            conversation.optionsLock.withLock {
                try {
                    agents.close(conversation.key)
                } catch (_: IOException) {
                    // A server that cannot be reached forgets them when it restarts, or is told the new ones.
                }
            }
        }
    }

    /**
     * Before a prompt: waits for the choices still on their way, so that the prompt runs with the model chosen, and
     * sends again those made before the session started (a server that restarted meanwhile forgot them).
     */
    internal suspend fun beforePrompt(conversation: Conversation) {
        conversation.optionsLock.withLock {
            val started = conversation.session != null
            val resent = if (started) emptyList() else conversation.choices.toList()
            for ((option, value) in resent) {
                agents.setOption(conversation.key, agentCommand(conversation), option, value)
            }
        }
    }

    // Once no choice is on its way, the settings as the server said them while one was: those a model brings.
    private fun Conversation.showServerOptions() {
        val settled = settingOptions == 0
        if (settled && serverOptions.isNotEmpty()) options = serverOptions
    }

    // The commands the agent offered in its latest session; none when the server cannot say.
    private suspend fun knownCommands(conversation: Conversation): List<AgentSlashCommand> =
        try {
            agents.commands(agentCommand(conversation))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            emptyList()
        }

    // The settings a session of the agent of [conversation] in [directory] offers; none when the server cannot say.
    private suspend fun offeredTo(conversation: Conversation, directory: String): List<AgentOption> =
        try {
            agents.options(agentCommand(conversation), directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            emptyList()
        }
}
