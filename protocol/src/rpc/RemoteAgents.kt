package org.example.kotlineditor.rpc

import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.model.AgentSessions
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.Agents

private const val CONVERSATION = "conversation"
private const val AGENT = "agent"
private const val DIRECTORY = "directory"
private const val SESSION = "session"

/** [Agents] run by the server. */
class RemoteAgents(private val client: RpcClient) : Agents {
    override suspend fun prompt(
        conversation: String,
        agent: AgentCommand,
        directory: String,
        itemId: String,
        text: String,
        session: String?,
    ) {
        client.call(Method.AgentPrompt) {
            put(CONVERSATION, conversation)
            put(AGENT, agent.toJson())
            put(DIRECTORY, directory)
            put("item", itemId)
            put("text", text)
            put(SESSION, session.orNull())
        }
    }

    override suspend fun open(conversation: String, agent: AgentCommand, directory: String, session: String) {
        client.call(Method.AgentOpen) {
            put(CONVERSATION, conversation)
            put(AGENT, agent.toJson())
            put(DIRECTORY, directory)
            put(SESSION, session)
        }
    }

    override suspend fun sessions(agent: AgentCommand, root: String): AgentSessions {
        val answer =
            client.call(Method.AgentSessions) {
                put(AGENT, agent.toJson())
                put("root", root)
            }
        return answer.agentSessions()
    }

    override suspend fun registry(): List<AgentRegistryEntry> = client.call(Method.AgentRegistry).agentRegistry()

    override suspend fun delete(agent: AgentCommand, session: String): Boolean {
        val answer =
            client.call(Method.AgentDelete) {
                put(AGENT, agent.toJson())
                put(SESSION, session)
            }
        return answer.jsonPrimitive.boolean
    }

    override suspend fun options(agent: AgentCommand, directory: String): List<AgentOption> {
        val answer =
            client.call(Method.AgentOptions) {
                put(AGENT, agent.toJson())
                put(DIRECTORY, directory)
            }
        return answer.agentOptions()
    }

    override suspend fun setOption(conversation: String, agent: AgentCommand, option: String, value: String) {
        client.call(Method.AgentSetOption) {
            put(CONVERSATION, conversation)
            put(AGENT, agent.toJson())
            put("option", option)
            put("value", value)
        }
    }

    override suspend fun cancel(conversation: String) {
        client.call(Method.AgentCancel) { put(CONVERSATION, conversation) }
    }

    override suspend fun answer(conversation: String, itemId: String, option: String) {
        client.call(Method.AgentAnswer) {
            put(CONVERSATION, conversation)
            put("item", itemId)
            put("option", option)
        }
    }

    override suspend fun transcript(conversation: String, epoch: String, since: Long): AgentTranscript {
        val answer =
            client.call(Method.AgentTranscript) {
                put(CONVERSATION, conversation)
                put("epoch", epoch)
                put("since", since)
            }
        return answer.agentTranscript()
    }

    override suspend fun close(conversation: String) {
        client.call(Method.AgentClose) { put(CONVERSATION, conversation) }
    }
}
