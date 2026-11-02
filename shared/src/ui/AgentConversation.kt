package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentMessage
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation

private val CONTENT_WIDTH = 760.dp

// One conversation of agent mode: its messages, tool calls and the prompt.

@Composable
internal fun ConversationView(agent: AgentState, conversation: Conversation) {
    val listState = conversation.listState
    // Follow the conversation: a new message scrolls to the bottom.
    LaunchedEffect(conversation.messages.size) {
        if (conversation.messages.isNotEmpty()) listState.animateScrollToItem(conversation.messages.lastIndex)
    }
    Column(Modifier.fillMaxSize().testTag(AppTags.agentView(conversation.id))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (conversation.messages.isEmpty()) {
                Label(
                    "Ask the agent about this project.",
                    Modifier.align(Alignment.Center),
                    color = IdeColors.TextSecondary,
                )
            }
            SelectionContainer {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    state = listState,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    contentPadding = PaddingValues(vertical = 16.dp),
                ) {
                    itemsIndexed(conversation.messages) { index, message ->
                        Box(
                            Modifier
                                .widthIn(max = CONTENT_WIDTH)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .testTag(AppTags.agentMessage(index)),
                        ) {
                            MessageView(message)
                        }
                    }
                    if (conversation.busy && conversation.messages.lastOrNull() !is AgentMessage.ToolCall) {
                        item {
                            Label(
                                "Thinking…",
                                Modifier.widthIn(max = CONTENT_WIDTH).fillMaxWidth().padding(horizontal = 20.dp),
                                color = IdeColors.TextSecondary,
                            )
                        }
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        Prompt(agent, conversation)
    }
}

private val bodyStyle = TextStyle(color = IdeColors.Text, fontSize = 13.sp, lineHeight = 20.sp)

@Composable
private fun MessageView(message: AgentMessage) {
    when (message) {
        is AgentMessage.User -> {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                BasicText(
                    message.text,
                    Modifier
                        .widthIn(max = 560.dp)
                        .background(IdeColors.Panel, RoundedCornerShape(8.dp))
                        .border(1.dp, IdeColors.Separator, RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    style = bodyStyle,
                )
            }
        }

        is AgentMessage.Agent -> {
            BasicText(message.text, Modifier.fillMaxWidth(), style = bodyStyle)
        }

        is AgentMessage.ToolCall -> {
            ToolCallCard(message)
        }
    }
}

@Composable
private fun ToolCallCard(call: AgentMessage.ToolCall) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(8.dp)
                .background(if (call.running) IdeColors.Accent else IdeColors.GitAdded, CircleShape),
        )
        Label(call.tool, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
        BasicText(
            call.target,
            Modifier.padding(start = 8.dp).weight(1f),
            style = LocalCodeStyleSmall(),
            maxLines = 1,
        )
        Label(
            if (call.running) "running…" else call.output,
            Modifier.padding(start = 8.dp),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun LocalCodeStyleSmall(): TextStyle =
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
                        .testTag(AppTags.AGENT_PROMPT)
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
        Label("Simulated agent: no model connected", color = IdeColors.TextSecondary, fontSize = 12.sp)
        Spacer(Modifier.weight(1f))
        if (conversation.busy) {
            IdeButton("Stop", { agent.stop(conversation) }, Modifier.testTag(AppTags.AGENT_STOP))
        } else {
            IdeButton(
                "Send",
                { agent.send(conversation) },
                Modifier.testTag(AppTags.AGENT_SEND),
                primary = true,
                enabled = conversation.draft.isNotBlank(),
            )
        }
    }
}
