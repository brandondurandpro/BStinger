package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentSession
import java.io.IOException

// The history of agent mode: the sessions the agent keeps, listed and opened through the server. The IDE stores none
// of it, so it never shows a history the agent no longer has.

// Characters of an ISO 8601 date: `2026-11-03`.
private const val DATE = 10

// These agents of the registry the history lists at most, those of the conversations that ran them: each starts its
// own process to list its sessions.
private const val MAX_REMEMBERED_AGENTS = 3

/** Lists the sessions of the agents each time agent mode shows: the one of the settings, and the agents of the
 * registry the IDE remembers (its conversations ran them). Each agent's sessions in one list. */
internal fun AgentState.followHistory(project: AgentHost, agent: () -> AgentCommand) {
    project.scope.launch {
        snapshotFlow { project.agentMode }.filter { it }.collect {
            val agents = project.services.agents
            for (command in agentsOfHistory(project, agent)) {
                val listed = runCatching { agents.sessions(command, project.root.path) }.getOrNull()
                if (listed != null && listed.supported) addHistory(listed.sessions, project.root.path)
            }
        }
    }
}

// The agents whose sessions the history shows: the one of the settings, then the agents of the registry the
// conversations ran with (the recent ones first, a few of them — each starts its own process to list them).
internal fun agentsOfHistory(project: AgentHost, agent: () -> AgentCommand): List<AgentCommand> {
    val settings = listOf(agent())
    val remembered = project.rememberedAgents.take(MAX_REMEMBERED_AGENTS).map { AgentCommand(emptyList(), registry = it) }
    return distinctAgentsOf(settings + remembered)
}

// Several agents may be one and the same (the settings' one is also remembered, or two conversations switched).
private fun distinctAgentsOf(agent: List<AgentCommand>): List<AgentCommand> {
    val seen = HashSet<AgentCommand>()
    return agent.filter { seen.add(it) }
}

// The sessions no conversation shows yet, after the conversations, newest first.
private fun AgentState.addHistory(sessions: List<AgentSession>, root: String) {
    val shown = conversations.mapNotNull { it.session }.toSet()
    for (session in sessions.filter { it.id !in shown }) {
        val conversation = conversationOf(session, nextId++, root, project.rememberedAgent(session.id))
        // The state delivers the prompts queued while the agent answered (the transcript only says the turn is over).
        conversation.onTurnIdle = { deliverQueued(conversation) }
        conversations.add(conversation)
    }
}

/** A conversation of the history: the session [session] of the agent, in the project at [root] or a worktree. */
internal fun conversationOf(session: AgentSession, id: Int, root: String, agent: String? = null): Conversation {
    val branch = session.branch
    val title = session.title?.takeIf { it.isNotBlank() } ?: "Conversation of ${session.updatedAt.orEmpty().take(DATE)}"
    val conversation = Conversation(id, titleOf(title), branch ?: "agent/conversation-$id")
    conversation.agent = agent?.let { AgentCommand(emptyList(), registry = it) }
    conversation.session = session.id
    conversation.sessionDirectory = session.directory
    conversation.mode = if (branch == null || session.directory == root) WorkMode.Direct else WorkMode.Worktree
    return conversation
}

/** Opens the session of [conversation], a conversation of the history, on first use: the agent replays it. */
internal fun AgentHost.restore(conversation: Conversation, agent: AgentCommand) {
    val session = conversation.session
    val directory = conversation.sessionDirectory
    if (session == null || directory == null) return
    conversation.sessionDirectory = null
    followTranscript(conversation)
    scope.launch {
        try {
            services.agents.open(conversation.key, agent, directory, session)
        } catch (failure: IOException) {
            conversation.note("The conversation could not be opened: ${failure.message}")
        }
    }
}
