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

/**
 * Forks [session] of [agent] working in [directory] (`session/fork`); returns the id of the copy. Fails when the agent
 * does not announce it.
 */
suspend fun AgentProcesses.fork(agent: AgentCommand, session: String, directory: String): String {
    val process = withContext(Dispatchers.IO) { processFor(agent) }
    return process.forkSession(session, directory)
}

/** Logs in to [agent] with [method], one of [authMethods]; returns once the agent is done. */
suspend fun AgentProcesses.authenticate(agent: AgentCommand, method: String) {
    val process = withContext(Dispatchers.IO) { processFor(agent) }
    process.authenticate(method)
}
