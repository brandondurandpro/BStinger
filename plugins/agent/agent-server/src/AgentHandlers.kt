package org.example.kotlineditor.agent.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.acp.authMethods
import org.example.kotlineditor.acp.authenticate
import org.example.kotlineditor.acp.fork
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentSessions
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.agentAttachments
import org.example.kotlineditor.rpc.agentCommand
import org.example.kotlineditor.rpc.authMethodsToJson
import org.example.kotlineditor.rpc.commandsToJson
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.stringOrNull
import org.example.kotlineditor.rpc.toJson
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.ServerContribution

private const val CONVERSATION = "conversation"
private const val ITEM = "item"
private const val AGENT = "agent"
private const val DIRECTORY = "directory"
private const val SESSION = "session"

/** Found by the server through `META-INF/services`. */
class AgentServerContribution : ServerContribution {
    override fun handlers(services: LocalServices) = agentHandlers(services)
}

/** Agent mode: conversations with the agents, run by [AgentsComponent]. */
internal fun agentHandlers(services: LocalServices): Map<MethodSpec, Handler> {
    val component = services.agentsComponent()
    return conversationHandlers(component) + optionHandlers(component)
}

// The conversations: prompts, answers, transcripts, history.
private fun conversationHandlers(component: AgentsComponent): Map<MethodSpec, Handler> =
    mapOf(
        Method.AgentPrompt to {
            component.agents.prompt(
                params.string(CONVERSATION),
                params.read(AGENT) { it.agentCommand() },
                params.string(DIRECTORY),
                params.string(ITEM),
                params.string("text"),
                params.stringOrNull(SESSION),
                params["attachments"]?.agentAttachments().orEmpty(),
            )
            JsonNull
        },
        Method.AgentOpen to {
            component.agents.open(
                params.string(CONVERSATION),
                params.read(AGENT) { it.agentCommand() },
                params.string(DIRECTORY),
                params.string(SESSION),
            )
            JsonNull
        },
        Method.AgentSessions to {
            sessions(component, params.read(AGENT) { it.agentCommand() }, params.string("root")).toJson()
        },
        Method.AgentRegistry to {
            withContext(Dispatchers.IO) { component.agents.processes.registry() }.toJson()
        },
        Method.AgentDelete to {
            val agent = params.read(AGENT) { it.agentCommand() }
            val deleted = component.agents.processes.delete(agent, params.string(SESSION))
            JsonPrimitive(deleted)
        },
        Method.AgentCancel to {
            component.agents.cancel(params.string(CONVERSATION))
            JsonNull
        },
        Method.AgentAnswer to {
            component.agents.answer(params.string(CONVERSATION), params.string(ITEM), params.string("option"))
            JsonNull
        },
        Method.AgentTranscript to {
            component.agents
                .transcript(params.string(CONVERSATION), params.string("epoch"), params.long("since"))
                .toJson()
        },
        Method.AgentClose to {
            component.agents.close(params.string(CONVERSATION))
            JsonNull
        },
    )

// The settings of the sessions (the model…).
private fun optionHandlers(component: AgentsComponent): Map<MethodSpec, Handler> =
    mapOf(
        Method.AgentOptions to {
            val agent = params.read(AGENT) { it.agentCommand() }
            val options = component.agents.processes.options(agent, params.string(DIRECTORY))
            options.toJson()
        },
        Method.AgentSetOption to {
            val agent = params.read(AGENT) { it.agentCommand() }
            val conversation = params.string(CONVERSATION)
            component.agents.setOption(conversation, agent, params.string("option"), params.string("value"))
            JsonNull
        },
        Method.AgentAuthMethods to {
            val agent = params.read(AGENT) { it.agentCommand() }
            val methods = component.agents.processes.authMethods(agent)
            methods.authMethodsToJson()
        },
        Method.AgentFork to {
            val agent = params.read(AGENT) { it.agentCommand() }
            val forked = component.agents.processes.fork(agent, params.string("session"), params.string(DIRECTORY))
            JsonPrimitive(forked)
        },
        Method.AgentAuthenticate to {
            val agent = params.read(AGENT) { it.agentCommand() }
            component.agents.processes.authenticate(agent, params.string("method"))
            JsonNull
        },
        Method.AgentCommands to {
            val agent = params.read(AGENT) { it.agentCommand() }
            val commands = component.agents.processes.knownCommands(agent)
            commands.commandsToJson()
        },
    )

// The sessions of the project at [root] and of its worktrees of agent mode, each one with the branch it works on.
private suspend fun sessions(component: AgentsComponent, agent: AgentCommand, root: String): AgentSessions {
    val worktrees = withContext(Dispatchers.IO) { component.services.agentWorktrees(root) }
    val listed = component.agents.processes.sessions(agent, listOf(root) + worktrees.keys)
    return listed.copy(sessions = listed.sessions.map { it.copy(branch = worktrees[it.directory]) })
}
