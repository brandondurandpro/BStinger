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
import org.example.kotlineditor.state.reviewerModels
import org.example.kotlineditor.state.reviewReport
import org.example.kotlineditor.state.sendReviewToAuthor
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
import org.example.kotlineditor.model.AgentOption
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

/** "Send to the original agent": the report of a reviewer goes to the agent it reviewed, which fixes what is real. */
@Composable
fun SendReviewButton(agent: AgentState, conversation: Conversation) {
    if (conversation.reviewOf == null) return
    val enabled = !conversation.busy && reviewReport(conversation) != null
    IdeButton(
        "Send to the original agent",
        { agent.sendReviewToAuthor(conversation) },
        Modifier.testTag(AgentTags.AGENT_REVIEW_SEND),
        enabled = enabled,
        compact = true,
    )
}

/** What the reviewing agent looks at, ticked by the user, then the agent and its model, then the start. */
@Composable
private fun ReviewDialog(agent: AgentState, conversation: Conversation, close: () -> Unit) {
    var aspects by remember { mutableStateOf(DEFAULT_REVIEW_ASPECTS) }
    var reviewer by remember { mutableStateOf<String?>(null) }
    var models by remember { mutableStateOf<AgentOption?>(null) }
    var model by remember { mutableStateOf<String?>(null) }
    // The models are those of the agent chosen: read again when it changes, the one chosen before is forgotten.
    LaunchedEffect(reviewer) {
        model = null
        models = null
        models = agent.reviewerModels(reviewer)
    }
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
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label("Agent", Modifier.width(52.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
                ReviewerPicker(agent, reviewer) { reviewer = it }
                Spacer(Modifier.width(12.dp))
                ReviewerModelPicker(models, model) { model = it }
            }
            val settings = reviewerSettings(models, model)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IdeButton("Cancel", close, Modifier.testTag(AgentTags.AGENT_REVIEW_CANCEL))
                Spacer(Modifier.width(8.dp))
                val start = {
                    close()
                    agent.reviewWith(conversation, reviewer, aspects, settings)
                    Unit
                }
                IdeButton("Start", start, Modifier.testTag(AgentTags.AGENT_REVIEW_START), primary = true, enabled = aspects.isNotEmpty())
            }
        }
    }
}

// The setting that gives the session the model chosen; none while the choice is the agent's own.
private fun reviewerSettings(models: AgentOption?, model: String?): Map<String, String> {
    if (models == null || model == null) return emptyMap()
    return mapOf(models.id to model)
}

/** The agent that reviews: the one of the settings, or one of the registry. */
@Composable
private fun ReviewerPicker(agent: AgentState, chosen: String?, choose: (String?) -> Unit) {
    val tags = RegistryTags(AgentTags.AGENT_REVIEW_DEFAULT, AgentTags.AGENT_REVIEW_FILTER, AgentTags::agentReviewer)
    val nameOf: (List<AgentRegistryEntry>?) -> String = { entries ->
        val named = entries?.firstOrNull { it.id == chosen }?.name
        if (chosen == null) agent.settingsAgentLabel else named ?: chosen
    }
    val button = RegistryButton("Agent", "Choose the agent that reviews", AgentTags.AGENT_REVIEW_AGENT, tags, chosen, nameOf)
    RegistryAgentButton(agent, button, enabled = true, choose)
}

/** The model of the reviewing agent among those it offers; "Default" leaves the choice to the agent. */
@Composable
private fun ReviewerModelPicker(models: AgentOption?, chosen: String?, choose: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val shown = models?.values?.firstOrNull { it.value == chosen }?.name ?: "Default model"
    Box {
        Label(
            "$shown ▾",
            Modifier
                .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
                .clickable(enabled = models != null) { open = true }
                .testTag(AgentTags.AGENT_REVIEW_MODEL)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = if (models != null) IdeColors.TextSecondary else IdeColors.LineNumber,
            fontSize = 12.sp,
        )
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).closesOnEscape { open = false }) {
            val pick: (String?) -> Unit = { value ->
                open = false
                choose(value)
            }
            IdeMenuItem(
                text = { Text("Default model", fontSize = 13.sp) },
                onClick = { pick(null) },
                modifier = Modifier.testTag(AgentTags.agentReviewModel("")),
            )
            for (value in models?.values.orEmpty()) {
                IdeMenuItem(
                    text = { Text(value.name, fontSize = 13.sp) },
                    onClick = { pick(value.value) },
                    modifier = Modifier.testTag(AgentTags.agentReviewModel(value.value)),
                )
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
private class RegistryButton(
    val label: String,
    val tooltip: String,
    val tag: String,
    val tags: RegistryTags,
    /** The agent marked as chosen in its menu, and what the button says given the registry (its name, once read). */
    val chosenId: String? = null,
    val text: (List<AgentRegistryEntry>?) -> String = { label },
)

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
                "${button.text(entries)} ▾",
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
            val menu = RegistryMenu(agent.settingsAgentLabel, chosenId = button.chosenId, tags = button.tags)
            AgentRegistryMenu(menu, entries, failure, picked)
        }
    }
}
