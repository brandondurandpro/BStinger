package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentCommand

/** Reads the quota of the conversation shown as soon as the agent has listed its commands and is free. */
internal fun AgentState.followQuota() {
    // The quota is the account's, not the conversation's: what one conversation read, the others of the agent show at once.
    project.scope.launch {
        snapshotFlow { current?.let { Pair(it, it.quota) } }
            .distinctUntilChanged()
            .collect { shown -> if (shown != null && shown.second.isNotEmpty()) quotaOfAgents[agentLabelOf(shown.first)] = shown.second }
    }
    project.scope.launch {
        snapshotFlow { current?.let { Pair(it, listOf(it.commands.size, it.busy, it.quotaStale, it.items.isEmpty())) } }
            .distinctUntilChanged()
            .collect { shown ->
                if (shown == null) return@collect
                adoptKnownQuota(shown.first)
                refreshQuota(shown.first)
            }
    }
}

// A conversation without quota takes the one another conversation of its agent read: no question asked.
private fun AgentState.adoptKnownQuota(conversation: Conversation) {
    if (conversation.quota.isNotEmpty()) return
    val known = quotaOfAgents[agentLabelOf(conversation)] ?: return
    conversation.quota = known
}

// The agents whose quota the IDE reads without being asked: the ones it knows the answer of (Claude and Codex).
private val QUOTA_AGENTS = listOf("claude", "codex")

internal fun readsQuotaAlone(agent: AgentCommand): Boolean {
    val registry = agent.registry.orEmpty()
    return QUOTA_AGENTS.any { registry.contains(it, ignoreCase = true) }
}
