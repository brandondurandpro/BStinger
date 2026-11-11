package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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

// Past this many values, the menu has a field to search them.
private const val SEARCHABLE_FROM = 5

// A menu shows a few values at once, the rest comes scrolling in place.
private val MENU_HEIGHT = 300.dp

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
                    .background(if (open) IdeColors.Selection else Color.Transparent, IdeShapes.Medium)
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
        IdeDropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).then(escape)) {
            // A long list of values (the models of a provider) has a field that filters it.
            var filter by remember { mutableStateOf("") }
            val searchable = option.values.size > SEARCHABLE_FROM
            if (searchable) {
                AgentFilterField(filter, "$tag.filter", "Search ${option.name.lowercase()}…") { filter = it }
            }
            val shown = option.values.filter { it.matches(filter) }
            if (shown.isEmpty()) {
                Label("""No match for "$filter".""", Modifier.padding(12.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
            // The values may be longer than a menu shows: capped at a few rows, scrolled in place.
            Column(Modifier.heightIn(max = MENU_HEIGHT).verticalScroll(rememberScrollState())) {
                for (value in shown) {
                    OptionItem(option, value, valueTag(value.value)) {
                        open = false
                        agent.options.choose(conversation, option.id, value.value)
                    }
                }
            }
        }
    }
}

// The name, the value or the description of [this] has [filter] in it, case ignored; all match an empty filter.
private fun AgentOptionValue.matches(filter: String): Boolean {
    val wanted = filter.trim()
    if (wanted.isEmpty()) return true
    val inName = name.contains(wanted, ignoreCase = true)
    val inValue = value.contains(wanted, ignoreCase = true)
    val inDescription = description?.contains(wanted, ignoreCase = true) == true
    return inName || inValue || inDescription
}

@Composable
private fun OptionItem(option: AgentOption, value: AgentOptionValue, tag: String, choose: () -> Unit) {
    val chosen = value.value == option.current
    IdeMenuItem(
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
