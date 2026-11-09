package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.IOException
import kotlinx.coroutines.CancellationException
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.discussWith
import org.example.kotlineditor.state.reviewWith

private val MENU_WIDTH = 320.dp

/**
 * "Review ▾" under the prompt: a click lists the agents the server can run, and the one chosen reviews the work of
 * [conversation] in a conversation of its own, in the same worktree. Offered once the agent has worked and is idle.
 */
@Composable
fun ReviewButton(agent: AgentState, conversation: Conversation) {
    val enabled = conversation.started && !conversation.busy
    val tags = RegistryTags(AgentTags.AGENT_REVIEW_DEFAULT, AgentTags.AGENT_REVIEW_FILTER, AgentTags::agentReviewer)
    val button = RegistryButton("Review", "Ask another agent to review this work", AgentTags.AGENT_REVIEW, tags)
    RegistryAgentButton(agent, button, enabled) { entry -> agent.reviewWith(conversation, entry) }
}

/**
 * "Discuss ▾" under the prompt: the agent chosen talks the request typed in the prompt over with the agent of
 * [conversation], which asks it questions until they agree on the best solution, then carries it out.
 */
@Composable
fun DiscussButton(agent: AgentState, conversation: Conversation) {
    val enabled = conversation.draft.isNotBlank() && !conversation.busy
    val tags = RegistryTags(AgentTags.AGENT_DISCUSS_DEFAULT, AgentTags.AGENT_DISCUSS_FILTER, AgentTags::agentDiscussPeer)
    val tooltip = "Type a request, then choose a second agent: they discuss it before anything is changed"
    val button = RegistryButton("Discuss", tooltip, AgentTags.AGENT_DISCUSS, tags)
    RegistryAgentButton(agent, button, enabled) { entry ->
        val request = conversation.draft.trim()
        conversation.draft = ""
        agent.discussWith(conversation, entry, request)
    }
}

/** What a button that lists the agents of the registry shows: its [label], its tooltip, its tag and those of its menu. */
private class RegistryButton(val label: String, val tooltip: String, val tag: String, val tags: RegistryTags)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegistryAgentButton(agent: AgentState, button: RegistryButton, enabled: Boolean, choose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<AgentRegistryEntry>?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    val agents = agent.project.services.agents
    LaunchedEffect(agents) {
        try {
            entries = agents.registry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (refused: IOException) {
            failure = refused.message ?: "unknown error"
        }
    }
    Box {
        TooltipArea(tooltip = { Tooltip(button.tooltip) }) {
            Label(
                "${button.label} ▾",
                Modifier
                    .background(if (open) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled) { open = true }
                    .testTag(button.tag)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = if (enabled) IdeColors.TextSecondary else IdeColors.LineNumber,
                fontSize = 12.sp,
            )
        }
        val picked: (String?) -> Unit = { entry ->
            open = false
            choose(entry)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }) {
            val menu = RegistryMenu(agent.settingsAgentLabel, chosenId = null, tags = button.tags)
            AgentRegistryMenu(menu, entries, failure, picked)
        }
    }
}
