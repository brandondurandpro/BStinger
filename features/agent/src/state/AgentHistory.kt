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

/** Lists the sessions of the agent each time agent mode shows: those of the project and of its worktrees. */
internal fun AgentState.followHistory(project: AgentHost, agent: () -> AgentCommand) {
    project.scope.launch {
        snapshotFlow { project.agentMode }.filter { it }.collect {
            val agents = project.services.agents
            val listed = runCatching { agents.sessions(agent(), project.root.path) }.getOrNull()
            if (listed != null && listed.supported) addHistory(listed.sessions, project.root.path)
        }
    }
}

// The sessions no conversation shows yet, after the conversations, newest first.
private fun AgentState.addHistory(sessions: List<AgentSession>, root: String) {
    val shown = conversations.mapNotNull { it.session }.toSet()
    for (session in sessions.filter { it.id !in shown }) {
        val conversation = conversationOf(session, nextId++, root)
        // The state delivers the prompts queued while the agent answered (the transcript only says the turn is over).
        conversation.onTurnIdle = { deliverQueued(conversation) }
        conversations.add(conversation)
    }
}

/** A conversation of the history: the session [session] of the agent, in the project at [root] or a worktree. */
internal fun conversationOf(session: AgentSession, id: Int, root: String): Conversation {
    val branch = session.branch
    val title = session.title?.takeIf { it.isNotBlank() } ?: "Conversation of ${session.updatedAt.orEmpty().take(DATE)}"
    val conversation = Conversation(id, titleOf(title), branch ?: "agent/conversation-$id")
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
