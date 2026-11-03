package org.example.kotlineditor.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentSessions
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.agentCommand
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.stringOrNull
import org.example.kotlineditor.rpc.toJson

private const val CONVERSATION = "conversation"
private const val ITEM = "item"
private const val AGENT = "agent"
private const val DIRECTORY = "directory"
private const val SESSION = "session"

/** Agent mode: conversations with the agents, run by [LocalServices.agents]. */
internal fun agentHandlers(services: LocalServices): Map<Method, Handler> =
    mapOf(
        Method.AgentPrompt to {
            services.agents.prompt(
                params.string(CONVERSATION),
                params.read(AGENT) { it.agentCommand() },
                params.string(DIRECTORY),
                params.string(ITEM),
                params.string("text"),
                params.stringOrNull(SESSION),
            )
            JsonNull
        },
        Method.AgentOpen to {
            services.agents.open(
                params.string(CONVERSATION),
                params.read(AGENT) { it.agentCommand() },
                params.string(DIRECTORY),
                params.string(SESSION),
            )
            JsonNull
        },
        Method.AgentSessions to {
            sessions(services, params.read(AGENT) { it.agentCommand() }, params.string("root")).toJson()
        },
        Method.AgentRegistry to {
            withContext(Dispatchers.IO) { services.agents.processes.registry() }.toJson()
        },
        Method.AgentDelete to {
            val agent = params.read(AGENT) { it.agentCommand() }
            val deleted = services.agents.processes.delete(agent, params.string(SESSION))
            JsonPrimitive(deleted)
        },
        Method.AgentCancel to {
            services.agents.cancel(params.string(CONVERSATION))
            JsonNull
        },
        Method.AgentAnswer to {
            services.agents.answer(params.string(CONVERSATION), params.string(ITEM), params.string("option"))
            JsonNull
        },
        Method.AgentTranscript to {
            services.agents
                .transcript(params.string(CONVERSATION), params.string("epoch"), params.long("since"))
                .toJson()
        },
        Method.AgentClose to {
            services.agents.close(params.string(CONVERSATION))
            JsonNull
        },
    )

// The sessions of the project at [root] and of its worktrees of agent mode, each one with the branch it works on.
private suspend fun sessions(services: LocalServices, agent: AgentCommand, root: String): AgentSessions {
    val worktrees = withContext(Dispatchers.IO) { agentWorktrees(root) }
    val listed = services.agents.processes.sessions(agent, listOf(root) + worktrees.keys)
    return listed.copy(sessions = listed.sessions.map { it.copy(branch = worktrees[it.directory]) })
}
