package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import java.io.IOException

/**
 * The settings of the sessions of the conversations (the model…): the agent offers them, the user chooses one in the
 * conversation, the server sets it in the session (as soon as it starts, for a conversation without one yet). The
 * IDE keeps none of them: they are the session's, as the agent says.
 */
@Stable
class ConversationOptions internal constructor(
    private val project: AgentHost,
    private val agentCommand: () -> AgentCommand,
) {
    private val agents get() = project.services.agents

    /** Asks the server what a new session offers, for [conversation] while it shows no settings. */
    internal fun offer(conversation: Conversation) {
        val asking = conversation.offering?.isActive == true
        if (conversation.options.isNotEmpty() || asking) return
        conversation.offering =
            project.scope.launch {
                val offered = offeredTo(project.root.path)
                if (conversation.options.isEmpty()) conversation.options = offered
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
                    agents.setOption(conversation.key, agentCommand(), option, value)
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
     * Before a prompt: waits for the choices still on their way, so that the prompt runs with the model chosen, and
     * sends again those made before the session started (a server that restarted meanwhile forgot them).
     */
    internal suspend fun beforePrompt(conversation: Conversation) {
        conversation.optionsLock.withLock {
            val started = conversation.session != null
            val resent = if (started) emptyList() else conversation.choices.toList()
            for ((option, value) in resent) {
                agents.setOption(conversation.key, agentCommand(), option, value)
            }
        }
    }

    // Once no choice is on its way, the settings as the server said them while one was: those a model brings.
    private fun Conversation.showServerOptions() {
        val settled = settingOptions == 0
        if (settled && serverOptions.isNotEmpty()) options = serverOptions
    }

    // The settings a session of the agent in [directory] offers; none when the server cannot say.
    private suspend fun offeredTo(directory: String): List<AgentOption> =
        try {
            agents.options(agentCommand(), directory)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            emptyList()
        }
}
