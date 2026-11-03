package org.example.kotlineditor.ui

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

/**
 * The model of the session of [conversation], under the prompt: a click lists the models the agent offers, the one
 * chosen applies from the next prompt on. Nothing shows when the agent lets the user choose none.
 */
@Composable
fun ModelPicker(agent: AgentState, conversation: Conversation) {
    val model = conversation.model ?: return
    var open by remember { mutableStateOf(false) }
    val current = model.values.firstOrNull { it.value == model.current }
    Box {
        Label(
            "${current?.name ?: model.current} ▾",
            Modifier
                .background(if (open) IdeColors.Selection else Color.Transparent, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .testTag(AgentTags.AGENT_MODEL)
                .padding(horizontal = 6.dp, vertical = 2.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        // Escape closes it without a choice, as every menu of the IDE.
        val escape =
            Modifier.onPreviewKeyEvent { event ->
                val closes = event.key == Key.Escape && event.type == KeyEventType.KeyDown
                if (closes) open = false
                closes
            }
        DropdownMenu(open, onDismissRequest = { open = false }, Modifier.widthIn(max = MENU_WIDTH).then(escape)) {
            for (value in model.values) {
                ModelItem(model, value) {
                    open = false
                    agent.options.choose(conversation, model.id, value.value)
                }
            }
        }
    }
}

@Composable
private fun ModelItem(model: AgentOption, value: AgentOptionValue, choose: () -> Unit) {
    val chosen = value.value == model.current
    DropdownMenuItem(
        text = {
            Column {
                val mark = if (chosen) "✓  " else "     "
                Label(mark + value.name, fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal)
                value.description?.let { Label(it, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = 2) }
            }
        },
        onClick = choose,
        modifier = Modifier.testTag(AgentTags.agentModel(value.value)),
    )
}
