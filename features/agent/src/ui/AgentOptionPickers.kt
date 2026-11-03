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
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation

private val MENU_WIDTH = 320.dp

// The settings of the session of a conversation, under its prompt: a click lists the values the agent offers, the one
// chosen applies from the next prompt on. Nothing shows for a setting the agent does not offer.

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
