package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.placeholder

// Left panel of agent mode: the conversations of the project, filtered by title.

@Composable
fun ConversationList(agent: AgentState, modifier: Modifier) {
    var filter by remember { mutableStateOf("") }
    Column(modifier.background(IdeColors.Panel).testTag(AgentTags.AGENT_LIST)) {
        Row(
            Modifier.fillMaxWidth().height(34.dp).padding(start = 12.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label("Conversations", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            IconButton({ agent.newConversation() }, Modifier.testTag(AgentTags.AGENT_NEW)) { PlusIcon() }
        }
        IdeTextField(
            filter,
            { filter = it },
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag(AgentTags.AGENT_FILTER),
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
    var renaming by remember { mutableStateOf(false) }
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
                .testTag(AgentTags.agentConversation(conversation.id))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                if (renaming) {
                    TitleField(conversation.title, onDone = { title ->
                        renaming = false
                        title?.let { agent.rename(conversation, it) }
                    })
                } else {
                    Label(conversation.title, fontWeight = FontWeight.Medium)
                }
                Label(
                    agent.agentLabelOf(conversation),
                    Modifier.testTag(AgentTags.agentConversationAgent(conversation.id)),
                    color = IdeColors.Accent,
                    fontSize = 11.sp,
                )
                Label(conversation.preview, color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
            conversation.lastMessageAt?.let { LastMessageAge(it, Modifier.padding(start = 6.dp)) }
            AgentStatus(conversation, Modifier.padding(start = 6.dp))
        }
        menu?.let { offset ->
            // The menu opens under its anchor: a point-sized one at the click puts it right there.
            Box(Modifier.offset(offset.x, offset.y).size(0.dp)) {
                DropdownMenu(true, onDismissRequest = { menu = null }) {
                    DropdownMenuItem(
                        text = { Text("Rename", fontSize = 13.sp) },
                        onClick = {
                            menu = null
                            renaming = true
                        },
                        modifier = Modifier.testTag(AgentTags.AGENT_RENAME),
                    )
                    DropdownMenuItem(
                        text = { Text("Delete", fontSize = 13.sp) },
                        onClick = {
                            menu = null
                            agent.delete(conversation)
                        },
                        modifier = Modifier.testTag(AgentTags.AGENT_DELETE),
                    )
                }
            }
        }
    }
}

/**
 * The agent at work (a spinner and for how long), done and not looked at yet (a green check), or done and seen (a
 * grey check); nothing before its first prompt.
 */
@Composable
internal fun AgentStatus(conversation: Conversation, modifier: Modifier = Modifier) {
    val since = conversation.workingSince
    when {
        conversation.busy -> {
            Row(modifier.testTag(AgentTags.agentWorking(conversation.id)), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), color = IdeColors.Accent, strokeWidth = 2.dp)
                if (since != null) WorkingTime(since, Modifier.padding(start = 5.dp))
            }
        }

        conversation.finished && conversation.seen -> {
            CheckIcon(modifier.testTag(AgentTags.agentSeen(conversation.id)), IdeColors.TextSecondary)
        }

        conversation.finished -> {
            CheckIcon(modifier.testTag(AgentTags.agentDone(conversation.id)), IdeColors.GitAdded)
        }
    }
}

// How long ago the last message was: `now`, `5m`, `3h`, `10d`…, refreshed every half minute.
@Composable
private fun LastMessageAge(at: Long, modifier: Modifier) {
    val now by produceState(System.currentTimeMillis(), at) {
        while (true) {
            value = System.currentTimeMillis()
            delay(AGE_TICK_MILLIS)
        }
    }
    Label(
        ageText(now - at),
        modifier.testTag(AgentTags.AGENT_LAST_MESSAGE),
        color = IdeColors.TextSecondary,
        fontSize = 11.sp,
    )
}

private const val AGE_TICK_MILLIS = 30_000L

/** `now`, `5m`, `3h`, `10d`, `2mo`, `1y`: the biggest unit that fits in [millis]. */
internal fun ageText(millis: Long): String {
    val minutes = (millis / MILLIS_PER_MINUTE).coerceAtLeast(0)
    val hours = minutes / MINUTES_PER_HOUR
    val days = hours / HOURS_PER_DAY
    return when {
        days >= DAYS_PER_YEAR -> "${days / DAYS_PER_YEAR}y"
        days >= DAYS_PER_MONTH -> "${days / DAYS_PER_MONTH}mo"
        days > 0 -> "${days}d"
        hours > 0 -> "${hours}h"
        minutes > 0 -> "${minutes}m"
        else -> "now"
    }
}

private const val MILLIS_PER_MINUTE = 60_000L
private const val MINUTES_PER_HOUR = 60L
private const val HOURS_PER_DAY = 24L
private const val DAYS_PER_MONTH = 30L
private const val DAYS_PER_YEAR = 365L

// Seconds since [since], redrawn every second while shown.
@Composable
private fun WorkingTime(since: Long, modifier: Modifier) {
    val now by produceState(System.currentTimeMillis(), since) {
        while (true) {
            value = System.currentTimeMillis()
            delay(TICK_MILLIS)
        }
    }
    Label(durationText(now - since), modifier, color = IdeColors.TextSecondary, fontSize = 11.sp)
}

private const val TICK_MILLIS = 1000L

/** `12s`, `3m 05s`, `1h 02m`. */
internal fun durationText(millis: Long): String {
    val seconds = (millis / TICK_MILLIS).coerceAtLeast(0)
    val minutes = seconds / SECONDS_PER_MINUTE
    val hours = minutes / SECONDS_PER_MINUTE
    return when {
        hours > 0 -> "${hours}h ${(minutes % SECONDS_PER_MINUTE).toString().padStart(2, '0')}m"
        minutes > 0 -> "${minutes}m ${(seconds % SECONDS_PER_MINUTE).toString().padStart(2, '0')}s"
        else -> "${seconds}s"
    }
}

private const val SECONDS_PER_MINUTE = 60L

// The title being edited: Enter or leaving the field keeps it, Escape drops it. [onDone] gets null when dropped.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun TitleField(title: String, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(TextFieldValue(title, TextRange(0, title.length))) }
    val focus = remember { FocusRequester() }
    var finished by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val finish = { result: String? ->
        if (!finished) {
            finished = true
            onDone(result)
        }
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    BasicTextField(
        text,
        { text = it },
        Modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged {
                // The field starts unfocused: only a focus it then loses ends the edit.
                if (it.isFocused) focused = true else if (focused) finish(text.text)
            }
            .onPreviewKeyEvent { event ->
                val pressed = event.type == KeyEventType.KeyDown
                when {
                    pressed && event.key == Key.Escape -> {
                        finish(null)
                        true
                    }

                    pressed && event.key == Key.Enter -> {
                        finish(text.text)
                        true
                    }

                    else -> false
                }
            }
            .testTag(AgentTags.AGENT_RENAME_FIELD),
        singleLine = true,
        textStyle = TextStyle(color = IdeColors.Text, fontSize = 13.sp, fontWeight = FontWeight.Medium),
        cursorBrush = SolidColor(IdeColors.Text),
        decorationBox = { field ->
            val shape = RoundedCornerShape(4.dp)
            val border = if (focused) IdeColors.Accent else IdeColors.InputBorder
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(IdeColors.Editor, shape)
                    .border(1.dp, border, shape)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
            ) { field() }
        },
    )
}
