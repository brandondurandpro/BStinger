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
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.window.Dialog
import org.example.kotlineditor.state.DEFAULT_REVIEW_ASPECTS
import org.example.kotlineditor.state.ReviewAspect
import org.example.kotlineditor.state.reviewManually
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
private val DIALOG_WIDTH = 380.dp

/**
 * "Review ▾" in the card of the changes: "Manually" opens a review tab of the changed files, "By agent…" asks which
 * aspects to look at ([ReviewDialog]) then which agent reviews the work of [conversation] in a conversation of its
 * own, in the same worktree.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReviewButton(agent: AgentState, conversation: Conversation, modifier: Modifier = Modifier, asButton: Boolean = false) {
    var open by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }
    val byAgent = conversation.started && !conversation.busy
    Box(modifier) {
        TooltipArea(tooltip = { Tooltip("Review this work, yourself or with another agent") }) {
            if (asButton) {
                IdeButton("Review ▾", { open = true }, Modifier.testTag(AgentTags.AGENT_REVIEW), compact = true)
            } else {
                Label(
                    "Review ▾",
                    Modifier
                        .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
                        .clickable { open = true }
                        .testTag(AgentTags.AGENT_REVIEW)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.closesOnEscape { open = false }) {
            IdeMenuItem(
                text = { Text("Manually", fontSize = 13.sp) },
                onClick = {
                    open = false
                    agent.reviewManually(conversation)
                },
                modifier = Modifier.testTag(AgentTags.AGENT_REVIEW_MANUAL),
                enabled = conversation.changes.files.isNotEmpty(),
            )
            IdeMenuItem(
                text = { Text("By agent…", fontSize = 13.sp) },
                onClick = {
                    open = false
                    choosing = true
                },
                modifier = Modifier.testTag(AgentTags.AGENT_REVIEW_BY_AGENT),
                enabled = byAgent,
            )
        }
    }
    if (choosing) ReviewDialog(agent, conversation) { choosing = false }
}

/** What the reviewing agent looks at, ticked by the user, then the agent that starts the review. */
@Composable
private fun ReviewDialog(agent: AgentState, conversation: Conversation, close: () -> Unit) {
    var aspects by remember { mutableStateOf(DEFAULT_REVIEW_ASPECTS) }
    Dialog(onDismissRequest = close) {
        Column(
            Modifier
                .width(DIALOG_WIDTH)
                .background(IdeColors.Popup, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                .padding(16.dp)
                .testTag(AgentTags.AGENT_REVIEW_DIALOG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Label("Review by an agent", fontWeight = FontWeight.SemiBold)
            Label("What should it look at?", color = IdeColors.TextSecondary, fontSize = 12.sp)
            for (aspect in ReviewAspect.entries) {
                val checked = aspect in aspects
                AspectRow(aspect, checked) { aspects = if (checked) aspects - aspect else aspects + aspect }
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IdeButton("Cancel", close, Modifier.testTag(AgentTags.AGENT_REVIEW_CANCEL))
                Spacer(Modifier.width(8.dp))
                val tags = RegistryTags(AgentTags.AGENT_REVIEW_DEFAULT, AgentTags.AGENT_REVIEW_FILTER, AgentTags::agentReviewer)
                val button = RegistryButton("Start", "Choose the agent that reviews", AgentTags.AGENT_REVIEW_START, tags)
                RegistryAgentButton(agent, button, aspects.isNotEmpty()) { entry ->
                    close()
                    agent.reviewWith(conversation, entry, aspects)
                }
            }
        }
    }
}

@Composable
private fun AspectRow(aspect: ReviewAspect, checked: Boolean, onToggle: () -> Unit) {
    val shape = IdeShapes.Small
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .testTag(AgentTags.agentReviewAspect(aspect.name))
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(if (checked) IdeColors.Accent else Color.Transparent, shape)
                .border(1.dp, if (checked) IdeColors.Accent else IdeColors.InputBorder, shape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) CheckIcon(Modifier.size(9.dp), color = IdeColors.OnAccent)
        }
        Label(aspect.label, Modifier.padding(start = 8.dp))
    }
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
                    .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
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
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }) {
            val menu = RegistryMenu(agent.settingsAgentLabel, chosenId = null, tags = button.tags)
            AgentRegistryMenu(menu, entries, failure, picked)
        }
    }
}
