package org.example.kotlineditor.state

import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.AGENT_LOGIN_MESSAGE
import org.example.kotlineditor.model.AgentAuthMethod
import org.example.kotlineditor.model.AgentItem

/** True when [item] is the notice of an agent that asks the user to log in first. */
fun isLoginNotice(item: AgentItem): Boolean = item is AgentItem.Notice && item.text.startsWith(AGENT_LOGIN_MESSAGE)

/** The ways to log in to the agent of [conversation]; empty when the agent lists none, or cannot be reached. */
suspend fun AgentState.loginMethods(conversation: Conversation): List<AgentAuthMethod> {
    val agents = project.services.agents
    return try {
        agents.authMethods(agentOf(conversation))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: IOException) {
        emptyList()
    }
}

/**
 * Logs in the agent of [conversation] with [method]; the conversation says how it went, and the user sends the prompt
 * again. Returns true when the agent accepted.
 */
suspend fun AgentState.logIn(conversation: Conversation, method: AgentAuthMethod): Boolean {
    val agents = project.services.agents
    return try {
        agents.authenticate(agentOf(conversation), method.id)
        conversation.note("Logged in with ${method.name}. Send your message again.", error = false)
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        conversation.note("Login failed: ${failure.message ?: "the agent refused"}")
        false
    }
}
