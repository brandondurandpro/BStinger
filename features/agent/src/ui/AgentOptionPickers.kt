package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import kotlinx.coroutines.CancellationException
import java.io.IOException

private val MENU_WIDTH = 320.dp

// The agent of a conversation and the settings of its session, under its prompt: a click lists the values, the one
// chosen applies from the next prompt on. Nothing shows for a setting the agent does not offer.

/**
 * The agent of [conversation]: a click lists the agents the server can run (its registry), the one chosen applies to
 * the next prompt of the conversation — the one of the settings before a choice, and an item brings it back.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AgentPicker(agent: AgentState, conversation: Conversation) {
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
        TooltipArea(tooltip = { Tooltip("Coding agent") }) {
            val button = buttonLabel(agent, conversation, entries)
            AgentPickerButton(button, open, onOpen = { open = true })
        }
        // Escape closes it without a choice, as every menu of the IDE.
        val escape =
            Modifier.onPreviewKeyEvent { event ->
                val closes = event.key == Key.Escape && event.type == KeyEventType.KeyDown
                if (closes) { open = false }
                closes
            }
        val choose: (String?) -> Unit = { entry ->
            open = false
            agent.chooseAgent(conversation, entry)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).then(escape)) {
            AgentPickerMenu(agent, conversation, entries, failure, choose)
        }
    }
}

@Composable
private fun AgentPickerButton(label: String, open: Boolean, onOpen: () -> Unit) {
    Label(
        "$label ▾",
        Modifier
            .background(if (open) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
            .clickable(onClick = onOpen)
            .testTag(AgentTags.AGENT_AGENT)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
        maxLines = 2,
    )
}

// The name the registry gives the agent [id] the conversation runs, when it lists it.
private fun registryAgentName(id: String?, entries: List<AgentRegistryEntry>?): String? {
    val listed = entries ?: return null
    val registryId = id ?: return null
    val found = listed.firstOrNull { it.id == registryId }
    return found?.name
}

// The label of the agent of [conversation]: the agent chosen there (its name in the registry), else the agent the
// settings choose — its id in the registry, `Settings` when it comes from a command line (what it runs shows next
// to the prompt).
private fun buttonLabel(
    agent: AgentState,
    conversation: Conversation,
    entries: List<AgentRegistryEntry>?,
): String {
    val chosen = conversation.agent
    if (chosen != null) {
        val named = registryAgentName(chosen.registry, entries)
        return named ?: chosen.registry ?: "Settings"
    }
    return agent.settingsAgentId ?: "Settings"
}

@Composable
private fun AgentPickerMenu(
    agent: AgentState,
    conversation: Conversation,
    entries: List<AgentRegistryEntry>?,
    failure: String?,
    choose: (String?) -> Unit,
) {
    if (conversation.agent != null) {
        DropdownMenuItem(
            text = {
                Column {
                    Label("Agent of the settings", fontWeight = FontWeight.Normal)
                    Label(
                        agent.settingsAgentLabel,
                        color = IdeColors.TextSecondary,
                        fontSize = 12.sp,
                        maxLines = 2,
                    )
                }
            },
            onClick = { choose(null) },
            modifier = Modifier.testTag(AgentTags.AGENT_AGENT_DEFAULT),
        )
    }
    val listed = entries
    when {
        listed != null -> for (entry in listed) AgentChoiceItem(conversation, entry, choose)
        failure != null -> DisabledItem("The registry could not be read: $failure", closeMenu = { choose(null) })
        else -> DisabledItem("Reading the registry…", closeMenu = null)
    }
}

@Composable
private fun AgentChoiceItem(conversation: Conversation, entry: AgentRegistryEntry, choose: (String?) -> Unit) {
    val available = entry.unavailable == null
    val chosen = conversation.agent?.registry == entry.id
    val mark = if (chosen) "✓  " else "     "
    val title = mark + entry.name + " " + entry.version
    val description = entry.unavailable ?: entry.description
    DropdownMenuItem(
        text = {
            Column {
                Label(title, fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal)
                Label(description, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2)
            }
        },
        onClick = { choose(entry.id) },
        enabled = available,
        modifier = Modifier.testTag(AgentTags.agentAgent(entry.id)),
    )
}

@Composable
private fun DisabledItem(text: String, closeMenu: (() -> Unit)?) {
    DropdownMenuItem(
        text = { Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp) },
        onClick = { closeMenu?.invoke() },
        enabled = closeMenu != null,
        modifier = Modifier.testTag(AgentTags.AGENT_REGISTRY),
    )
}

/** The model of the session of [conversation]. */
@Composable
fun ModelPicker(agent: AgentState, conversation: Conversation) {
    val model = conversation.model ?: return
    OptionPicker(agent, conversation, model, AgentTags.AGENT_MODEL, AgentTags::agentModel)
}

/** The mode of permissions of the session of [conversation] (ask before editing, accept edits, plan…). */
@Composable
fun PermissionsPicker(agent: AgentState, conversation: Conversation) {
    val permissions = conversation.permissions ?: return
    OptionPicker(agent, conversation, permissions, AgentTags.AGENT_PERMISSIONS, AgentTags::agentPermissionMode)
}

/** The effort of the session of [conversation]: how hard the agent thinks, when its model lets the user choose. */
@Composable
fun EffortPicker(agent: AgentState, conversation: Conversation) {
    val effort = conversation.effort ?: return
    OptionPicker(agent, conversation, effort, AgentTags.AGENT_EFFORT, AgentTags::agentEffort)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OptionPicker(
    agent: AgentState,
    conversation: Conversation,
    option: AgentOption,
    tag: String,
    valueTag: (String) -> String,
) {
    var open by remember { mutableStateOf(false) }
    val current = option.values.firstOrNull { it.value == option.current }
    Box {
        // Its name in a tooltip: the button shows the value alone (`Sonnet`, `Accept edits`).
        TooltipArea(tooltip = { Tooltip(option.name) }) {
            Label(
                "${current?.name ?: option.current} ▾",
                Modifier
                    .background(if (open) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
                    .clickable { open = true }
                    .testTag(tag)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        // Escape closes it without a choice, as every menu of the IDE.
        val escape =
            Modifier.onPreviewKeyEvent { event ->
                val closes = event.key == Key.Escape && event.type == KeyEventType.KeyDown
                if (closes) open = false
                closes
            }
        DropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).then(escape)) {
            for (value in option.values) {
                OptionItem(option, value, valueTag(value.value)) {
                    open = false
                    agent.options.choose(conversation, option.id, value.value)
                }
            }
        }
    }
}

@Composable
private fun OptionItem(option: AgentOption, value: AgentOptionValue, tag: String, choose: () -> Unit) {
    val chosen = value.value == option.current
    DropdownMenuItem(
        text = {
            Column {
                val mark = if (chosen) "✓  " else "     "
                Label(mark + value.name, fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal)
                value.description?.let { Label(it, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2) }
            }
        },
        onClick = choose,
        modifier = Modifier.testTag(tag),
    )
}
