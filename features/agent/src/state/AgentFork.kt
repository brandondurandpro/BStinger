package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentSession
import java.io.IOException
import java.time.Instant

/**
 * Duplicates [conversation] (`session/fork`): the agent copies the history of its session under a new one, which
 * opens as a conversation of its own, in the same directory. A conversation that did not start has nothing to copy.
 */
fun AgentState.duplicate(conversation: Conversation) {
    val session = conversation.session
    if (session == null) {
        conversation.note("There is nothing to duplicate yet: send a first message.")
        return
    }
    val workspace = conversation.workspace ?: project
    val directory = workspace.root.path
    val agents = project.services.agents
    project.scope.launch {
        try {
            val forked = agents.fork(agentOf(conversation), session, directory)
            val worktree = conversation.mode == WorkMode.Worktree
            val copy = AgentSession(forked, directory, "${conversation.title} (copy)", Instant.now().toString())
            val shown = conversationOf(copy.copy(branch = conversation.branch.takeIf { worktree }), nextId++, project.root.path)
            shown.agent = conversation.agent
            // The agent forwards the title of the original: the copy keeps its own.
            shown.renamed = true
            shown.onTurnIdle = { deliverQueued(shown) }
            conversations.add(0, shown)
            open(shown)
        } catch (failure: IOException) {
            conversation.note("The conversation could not be duplicated: ${failure.message}")
        }
    }
}
