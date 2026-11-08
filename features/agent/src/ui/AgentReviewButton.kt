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
import org.example.kotlineditor.state.reviewWith

private val MENU_WIDTH = 300.dp

/**
 * "Review ▾" under the prompt: a click lists the agents the server can run, and the one chosen reviews the work of
 * [conversation] in a conversation of its own, in the same worktree. Offered once the agent has worked and is idle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewButton(agent: AgentState, conversation: Conversation) {
    var open by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<AgentRegistryEntry>?>(null) }
    val agents = agent.project.services.agents
    LaunchedEffect(agents) {
        try {
            entries = agents.registry()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            entries = emptyList()
        }
    }
    val enabled = conversation.started && !conversation.busy
    Box {
        TooltipArea(tooltip = { Tooltip("Ask another agent to review this work") }) {
            Label(
                "Review ▾",
                Modifier
                    .background(if (open) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable(enabled = enabled) { open = true }
                    .testTag(AgentTags.AGENT_REVIEW)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = if (enabled) IdeColors.TextSecondary else IdeColors.LineNumber,
                fontSize = 12.sp,
            )
        }
        DropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH)) {
            ReviewerRows(agent, conversation, entries) { open = false }
        }
    }
}

@Composable
private fun ReviewerRows(agent: AgentState, conversation: Conversation, entries: List<AgentRegistryEntry>?, close: () -> Unit) {
    val review = { entry: String? ->
        close()
        agent.reviewWith(conversation, entry)
    }
    ReviewerRow("Agent of the settings", agent.settingsAgentLabel, true, AgentTags.AGENT_REVIEW_DEFAULT) { review(null) }
    if (entries == null) Label("Reading the registry…", Modifier.padding(12.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
    for (entry in entries.orEmpty()) {
        val description = entry.unavailable ?: entry.description
        ReviewerRow(entry.name, description, entry.unavailable == null, AgentTags.agentReviewer(entry.id)) { review(entry.id) }
    }
}

@Composable
private fun ReviewerRow(title: String, subtitle: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Label(title, color = if (enabled) IdeColors.Text else IdeColors.TextSecondary)
            Label(subtitle, color = IdeColors.TextSecondary, fontSize = 11.sp, maxLines = 1)
        }
    }
}
