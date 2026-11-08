package org.example.kotlineditor.state

import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AgentCommand

/** Reads the quota of the conversation shown as soon as the agent has listed its commands and is free. */
internal fun AgentState.followQuota() {
    project.scope.launch {
        snapshotFlow { current?.let { Pair(it, listOf(it.commands.size, it.busy, it.quotaStale)) } }
            .distinctUntilChanged()
            .collect { shown -> if (shown != null) refreshQuota(shown.first) }
    }
}

// The agents whose quota the IDE reads without being asked: the ones it knows the answer of (Claude and Codex).
private val QUOTA_AGENTS = listOf("claude", "codex")

internal fun readsQuotaAlone(agent: AgentCommand): Boolean {
    val registry = agent.registry.orEmpty()
    return QUOTA_AGENTS.any { registry.contains(it, ignoreCase = true) }
}
