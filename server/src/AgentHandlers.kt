package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonNull
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.agentCommand
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.toJson

private const val CONVERSATION = "conversation"
private const val ITEM = "item"

/** Agent mode: conversations with the agents, run by [LocalServices.agents]. */
internal fun agentHandlers(services: LocalServices): Map<Method, Handler> =
    mapOf(
        Method.AgentPrompt to {
            services.agents.prompt(
                params.string(CONVERSATION),
                params.read("agent") { it.agentCommand() },
                params.string("directory"),
                params.string(ITEM),
                params.string("text"),
            )
            JsonNull
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
