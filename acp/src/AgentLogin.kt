package org.example.kotlineditor.acp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.AgentAuthMethod
import org.example.kotlineditor.model.AgentCommand

/** The ways to log in to [agent]; the agent starts for it when needed. */
suspend fun AgentProcesses.authMethods(agent: AgentCommand): List<AgentAuthMethod> {
    val process = withContext(Dispatchers.IO) { processFor(agent) }
    return process.ready().authMethods
}

/** Logs in to [agent] with [method], one of [authMethods]; returns once the agent is done. */
suspend fun AgentProcesses.authenticate(agent: AgentCommand, method: String) {
    val process = withContext(Dispatchers.IO) { processFor(agent) }
    process.authenticate(method)
}
