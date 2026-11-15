package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentCommand

/** How often the quota shown is read again while the agent is free: the limits move with the other sessions too. */
internal const val QUOTA_REFRESH_MILLIS = 30_000L

// How often the IDE looks whether the quota shown is due to be read again.
private const val QUOTA_TICK_MILLIS = 1_000L

/**
 * Reads the quota of the conversation shown as soon as the agent has listed its commands and is free, after each of
 * its turns, and every [AgentState.quotaRefreshMillis] while agent mode shows it.
 */
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
    project.scope.launch {
        while (isActive) {
            delay(QUOTA_TICK_MILLIS)
            current?.let(::markQuotaDue)
        }
    }
}

// The quota shown is read again once [AgentState.quotaRefreshMillis] went by since it was last asked for (by any
// conversation of the agent: it is the account's). A busy agent reads it at the end of its turn.
private fun AgentState.markQuotaDue(conversation: Conversation) {
    if (!project.agentMode || conversation.quota.isEmpty() || conversation.quotaStale) return
    val askedAt = quotaAskedAt[agentLabelOf(conversation)] ?: 0L
    val elapsed = System.currentTimeMillis() - askedAt
    if (elapsed < quotaRefreshMillis) return
    // The collector above asks for it once the agent is free.
    conversation.quotaStale = true
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
