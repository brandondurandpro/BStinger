package org.example.kotlineditor.rpc

import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AgentChoices
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.model.AgentSlashCommand

private const val CONVERSATION = "conversation"
private const val AGENT = "agent"
private const val DIRECTORY = "directory"

/** [AgentChoices] made on the server: the agents to choose from, the settings and the commands they offer. */
internal class RemoteAgentChoices(private val client: RpcClient) : AgentChoices {
    override suspend fun registry(): List<AgentRegistryEntry> = client.call(Method.AgentRegistry).agentRegistry()

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

    override suspend fun commands(agent: AgentCommand): List<AgentSlashCommand> =
        client.call(Method.AgentCommands) { put(AGENT, agent.toJson()) }.agentSlashCommands()
}
