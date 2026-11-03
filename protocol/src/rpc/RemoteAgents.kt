package org.example.kotlineditor.rpc

import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentTranscript
import org.example.kotlineditor.model.Agents

private const val CONVERSATION = "conversation"

/** [Agents] run by the server. */
class RemoteAgents(private val client: RpcClient) : Agents {
    override suspend fun prompt(
        conversation: String,
        agent: AgentCommand,
        directory: String,
        itemId: String,
        text: String,
    ) {
        client.call(Method.AgentPrompt) {
            put(CONVERSATION, conversation)
            put("agent", agent.toJson())
            put("directory", directory)
            put("item", itemId)
            put("text", text)
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
