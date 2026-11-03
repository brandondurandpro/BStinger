package org.example.kotlineditor.acp

import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentSession
import java.io.IOException

// The sessions of an agent process: started, reopened, listed and deleted. The agent keeps them (and their history)
// itself: the server only knows their ids.

// Pages of `session/list` read at most, in case an agent sends the same cursor again and again.
private const val MAX_PAGES = 50

// What an agent answers to a request about a session it does not know (any more).
private const val RESOURCE_NOT_FOUND = -32_002

/** A session the agent started, and the settings it offers (null when the agent did not list them). */
internal class StartedSession(val id: String, val options: List<AgentOption>?)

/**
 * Starts a session working in [directory], whose updates go to [listener]; the settings it offers are remembered as
 * those of the next sessions of the agent.
 */
internal suspend fun AgentProcess.newSession(directory: String, listener: SessionListener): StartedSession {
    val answer = request("session/new", newSessionParams(directory))
    val id = answer?.text(SESSION_ID) ?: throw IOException("The agent started a session without an id")
    listen(id, listener)
    val options = answer.configOptions()
    offered = options.orEmpty()
    lastDirectory = directory
    return StartedSession(id, options)
}

/** `session/load`: reopens a session and replays its history. */
internal const val LOAD = "session/load"

/**
 * How to reopen a session: `session/load` (the agent replays the history, as updates, before it answers) when
 * [replay] asks for the history, `session/resume` otherwise; either one when the agent knows only one. Null when it
 * can do neither.
 */
internal fun reopenMethod(can: SessionCapabilities, replay: Boolean): String? =
    when {
        replay && can.load -> LOAD
        can.resume -> "session/resume"
        can.load -> LOAD
        else -> null
    }

/**
 * Reopens [session] in [directory] with [method], its updates going to [listener]; returns its settings (null when the
 * agent did not list them). Fails when the agent cannot.
 */
internal suspend fun AgentProcess.reopenSession(
    session: String,
    directory: String,
    listener: SessionListener,
    method: String,
): List<AgentOption>? {
    // A replay comes before the answer: the listener must hear it.
    listen(session, listener)
    try {
        return request(method, reopenParams(session, directory)).configOptions()
    } catch (failure: LspResponseException) {
        release(session)
        throw failure
    }
}

/** The sessions the agent keeps that work in [directory]; empty when it does not list them. */
internal suspend fun AgentProcess.listSessions(directory: String): List<AgentSession> {
    if (!ready().sessions.list) return emptyList()
    val found = mutableListOf<AgentSession>()
    var cursor: String? = null
    for (page in 1..MAX_PAGES) {
        val answer = request("session/list", listParams(directory, cursor))
        found += answer?.get("sessions").array().mapNotNull { it.obj()?.agentSession() }
        cursor = answer?.text("nextCursor") ?: break
    }
    return found.filter { it.directory == directory }
}

private fun JsonObject.agentSession(): AgentSession? {
    val id = text(SESSION_ID)
    val directory = text("cwd")
    if (id == null || directory == null) return null
    return AgentSession(id, directory, text("title"), text("updatedAt"))
}

/** Sends [text] to [session] and waits for the end of the turn; returns why it ended (`end_turn`, `cancelled`). */
internal suspend fun AgentProcess.prompt(session: String, text: String): String {
    val answer = request("session/prompt", promptParams(session, text))
    return answer?.text("stopReason") ?: "end_turn"
}

/** Frees [session] in the agent when it can, and forgets it; the agent keeps its history. */
internal suspend fun AgentProcess.closeSession(session: String) {
    release(session)
    val closes = runCatching { ready().sessions.close }.getOrDefault(false)
    if (closes && isAlive) runCatching { request("session/close", sessionParams(session)) }
}

/** Deletes [session] and its history in the agent; false when the agent cannot. A session already gone is deleted. */
internal suspend fun AgentProcess.deleteSession(session: String): Boolean {
    release(session)
    if (!ready().sessions.delete) return false
    try {
        request("session/delete", sessionParams(session))
    } catch (failure: LspResponseException) {
        if (failure.code != RESOURCE_NOT_FOUND) throw failure
    }
    return true
}
