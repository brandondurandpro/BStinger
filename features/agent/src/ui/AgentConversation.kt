package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation

private val CONTENT_WIDTH = 760.dp

// Far enough to reach the end of the last item, however long it grew.
private const val TO_THE_END = 100_000f

// One conversation of agent mode: its transcript (messages, tool calls, plans, permission requests) and the prompt.

@Composable
fun ConversationView(agent: AgentState, conversation: Conversation) {
    val listState = conversation.listState
    val items = conversation.items
    FollowTheEnd(conversation)
    Column(Modifier.fillMaxSize().testTag(AgentTags.agentView(conversation.id))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Label(
                    "Ask the agent about this project.",
                    Modifier.align(Alignment.Center),
                    color = IdeColors.TextSecondary,
                )
            }
            SelectionContainer { Transcript(agent, conversation) }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        Prompt(agent, conversation)
    }
}

// A new item, or the last one growing as it streams, scrolls to the bottom.
@Composable
private fun FollowTheEnd(conversation: Conversation) {
    val listState = conversation.listState
    val items = conversation.items
    val last = items.lastOrNull()
    LaunchedEffect(items.size, last?.version) {
        if (items.isNotEmpty()) {
            listState.scrollToItem(items.lastIndex)
            listState.scrollBy(TO_THE_END)
        }
    }
}

// The items, virtualized, and what the agent does while nothing shows yet.
@Composable
private fun Transcript(agent: AgentState, conversation: Conversation) {
    val listState = conversation.listState
    val items = conversation.items
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
            Box(
                Modifier
                    .widthIn(max = CONTENT_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .testTag(AgentTags.agentMessage(index)),
            ) {
                ItemView(agent, conversation, item)
            }
        }
        if (thinking(conversation)) {
            item(key = "thinking") { ThinkingLabel(conversation) }
        }
    }
}

@Composable
private fun ThinkingLabel(conversation: Conversation) {
    val starting = conversation.state == AgentTurnState.Starting
    Label(
        if (starting) "Starting the agent…" else "Thinking…",
        Modifier
            .widthIn(max = CONTENT_WIDTH)
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .testTag(AgentTags.AGENT_THINKING),
        color = IdeColors.TextSecondary,
    )
}

// Busy with nothing to show for it yet: no text streaming, no tool running, no question asked.
private fun thinking(conversation: Conversation): Boolean {
    if (!conversation.busy) return false
    return when (val last = conversation.items.lastOrNull()) {
        is AgentItem.AgentText -> false
        is AgentItem.ToolCall -> last.status != AgentToolStatus.Running
        is AgentItem.Permission -> !last.pending
        else -> true
    }
}

internal val bodyStyle = TextStyle(color = IdeColors.Text, fontSize = 13.sp, lineHeight = 20.sp)

@Composable
private fun ItemView(agent: AgentState, conversation: Conversation, item: AgentItem) {
    when (item) {
        is AgentItem.UserText -> UserBubble(item.text)
        is AgentItem.AgentText -> MarkdownText(item.text.trimEnd(), Modifier.fillMaxWidth())
        is AgentItem.Thought -> ThoughtView(item.text)
        is AgentItem.ToolCall -> ToolCallCard(item)
        is AgentItem.Plan -> PlanView(item)
        is AgentItem.Permission -> PermissionCard(item) { option -> agent.answer(conversation, item, option) }
        is AgentItem.Notice -> NoticeView(item)
    }
}

@Composable
internal fun LocalCodeStyleSmall(): TextStyle =
    LocalCodeStyle.current.copy(color = IdeColors.Code, fontSize = 12.sp, lineHeight = 18.sp)

// Enter sends, Shift+Enter starts a new line, like most chat inputs.
@Composable
private fun Prompt(agent: AgentState, conversation: Conversation) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Where the agent works (the current branch or a worktree), just above what is sent to it.
        val width = Modifier.widthIn(max = CONTENT_WIDTH).fillMaxWidth()
        WorkspaceStatus(agent, conversation, width.padding(horizontal = 4.dp, vertical = 6.dp))
        Column(
            width
                .background(IdeColors.Input, RoundedCornerShape(8.dp))
                .border(1.dp, IdeColors.InputBorder, RoundedCornerShape(8.dp))
                .padding(10.dp),
        ) {
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 200.dp)) {
                if (conversation.draft.isEmpty()) {
                    Label("Ask the agent… (Enter to send, Shift+Enter for a new line)", color = IdeColors.TextSecondary)
                }
                BasicTextField(
                    conversation.draft,
                    { conversation.draft = it },
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(AgentTags.AGENT_PROMPT)
                        .onPreviewKeyEvent { event -> sendsOnEnter(event) { agent.send(conversation) } },
                    textStyle = bodyStyle,
                    cursorBrush = SolidColor(IdeColors.Caret),
                )
            }
            PromptActions(agent, conversation)
        }
    }
}

private fun sendsOnEnter(event: KeyEvent, send: () -> Unit): Boolean {
    val enter = event.key == Key.Enter && !event.isShiftPressed
    if (enter && event.type == KeyEventType.KeyDown) send()
    return enter
}

@Composable
private fun PromptActions(agent: AgentState, conversation: Conversation) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The model first: the name of the agent (its command line, before it introduced itself) may be long.
        ModelPicker(agent, conversation)
        Spacer(Modifier.width(8.dp))
        val name = conversation.agentName ?: agent.agentLabel
        Label(
            name,
            Modifier.weight(1f).testTag(AgentTags.AGENT_NAME),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        if (conversation.busy) {
            IdeButton("Stop", { agent.stop(conversation) }, Modifier.testTag(AgentTags.AGENT_STOP))
        } else {
            IdeButton(
                "Send",
                { agent.send(conversation) },
                Modifier.testTag(AgentTags.AGENT_SEND),
                primary = true,
                enabled = conversation.draft.isNotBlank(),
            )
        }
    }
}
