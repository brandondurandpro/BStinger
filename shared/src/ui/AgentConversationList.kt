package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.placeholder

// Left panel of agent mode: the conversations of the project, filtered by title.

@Composable
internal fun ConversationList(agent: AgentState, modifier: Modifier) {
    var filter by remember { mutableStateOf("") }
    Column(modifier.background(IdeColors.Panel).testTag(Tags.AGENT_LIST)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Conversations", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton({ agent.newConversation() }, Modifier.testTag(Tags.AGENT_NEW)) { PlusIcon() }
        }
        IdeTextField(
            filter,
            { filter = it },
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(Tags.AGENT_FILTER),
            placeholder = "Search conversations",
            leading = { SearchIcon() },
        )
        Spacer(Modifier.height(6.dp))
        val shown = agent.conversations.filter { it.title.contains(filter.trim(), ignoreCase = true) }
        if (shown.isEmpty()) {
            Label(
                if (agent.conversations.isEmpty()) "No conversations" else "No match",
                Modifier.padding(12.dp),
                color = IdeColors.TextSecondary,
            )
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { conversation ->
                ConversationRow(agent, conversation, selected = conversation == agent.current)
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun ConversationRow(agent: AgentState, conversation: Conversation, selected: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var menu by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    val background =
        when {
            selected -> IdeColors.Selection
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    // Right click opens the menu of the conversation.
    val press =
        Modifier.onPointerEvent(PointerEventType.Press) { event ->
            if (event.button == PointerButton.Secondary) {
                val at = event.changes.first().position
                menu = with(density) { DpOffset(at.x.toDp(), at.y.toDp()) }
            }
        }
    Box {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 1.dp)
                .background(background, RoundedCornerShape(6.dp))
                .hoverable(interaction)
                .clickable(interaction, indication = null) { agent.open(conversation) }
                .then(press)
                .testTag(Tags.agentConversation(conversation.id))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Label(conversation.title, fontWeight = FontWeight.Medium)
                Label(conversation.preview, color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
            if (conversation.busy) RunningDot(Modifier.padding(start = 6.dp))
        }
        menu?.let { offset ->
            DropdownMenu(true, onDismissRequest = { menu = null }, offset = offset) {
                DropdownMenuItem(
                    text = { Text("Delete", fontSize = 13.sp) },
                    onClick = {
                        menu = null
                        agent.delete(conversation)
                    },
                    modifier = Modifier.testTag(Tags.AGENT_DELETE),
                )
            }
        }
    }
}

@Composable
internal fun RunningDot(modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).background(IdeColors.Accent, CircleShape))
}
