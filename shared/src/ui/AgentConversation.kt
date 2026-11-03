package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation

private val CONTENT_WIDTH = 760.dp

// The diff of a tool call shows at most these lines; the rest opens from the Git panel.
private const val DIFF_LINES = 12

// The output of a tool call shows at most these lines.
private const val OUTPUT_LINES = 6

// Far enough to reach the end of the last item, however long it grew.
private const val TO_THE_END = 100_000f

// One conversation of agent mode: its transcript (messages, tool calls, plans, permission requests) and the prompt.

@Composable
internal fun ConversationView(agent: AgentState, conversation: Conversation) {
    val listState = conversation.listState
    val items = conversation.items
    // Follow the conversation: a new item, or the last one growing as it streams, scrolls to the bottom.
    val last = items.lastOrNull()
    LaunchedEffect(items.size, last?.version) {
        if (items.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(items.lastIndex)
        listState.scrollBy(TO_THE_END)
    }
    Column(Modifier.fillMaxSize().testTag(AppTags.agentView(conversation.id))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
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
                    itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                        Box(
                            Modifier
                                .widthIn(max = CONTENT_WIDTH)
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                                .testTag(AppTags.agentMessage(index)),
                        ) {
                            ItemView(agent, conversation, item)
                        }
                    }
                    if (thinking(conversation)) {
                        item(key = "thinking") {
                            Label(
                                if (conversation.state == AgentTurnState.Starting) "Starting the agent…" else "Thinking…",
                                Modifier
                                    .widthIn(max = CONTENT_WIDTH)
                                    .fillMaxWidth()
                                    .padding(horizontal = 20.dp)
                                    .testTag(AppTags.AGENT_THINKING),
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

private val bodyStyle = TextStyle(color = IdeColors.Text, fontSize = 13.sp, lineHeight = 20.sp)

@Composable
private fun ItemView(agent: AgentState, conversation: Conversation, item: AgentItem) {
    when (item) {
        is AgentItem.UserText -> UserBubble(item.text)
        is AgentItem.AgentText -> BasicText(item.text.trimEnd(), Modifier.fillMaxWidth(), style = bodyStyle)
        is AgentItem.Thought -> ThoughtView(item.text)
        is AgentItem.ToolCall -> ToolCallCard(item)
        is AgentItem.Plan -> PlanView(item)
        is AgentItem.Permission -> PermissionCard(item) { option -> agent.answer(conversation, item, option) }
        is AgentItem.Notice -> NoticeView(item)
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        BasicText(
            text,
            Modifier
                .widthIn(max = 560.dp)
                .background(IdeColors.Panel, RoundedCornerShape(8.dp))
                .border(1.dp, IdeColors.Separator, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = bodyStyle,
        )
    }
}

@Composable
private fun ThoughtView(text: String) {
    BasicText(
        text.trim(),
        Modifier.fillMaxWidth().padding(start = 8.dp),
        style = bodyStyle.copy(color = IdeColors.TextSecondary, fontStyle = FontStyle.Italic, fontSize = 12.sp),
        maxLines = OUTPUT_LINES,
    )
}

@Composable
private fun NoticeView(notice: AgentItem.Notice) {
    BasicText(
        notice.text,
        Modifier.fillMaxWidth(),
        style = bodyStyle.copy(color = if (notice.error) IdeColors.Error else IdeColors.TextSecondary, fontSize = 12.sp),
    )
}

@Composable
private fun ToolCallCard(call: AgentItem.ToolCall) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(statusColor(call.status), CircleShape))
            Label(call.kind.name, Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            BasicText(
                call.title,
                Modifier.padding(start = 8.dp).weight(1f),
                style = LocalCodeStyleSmall(),
                maxLines = 1,
            )
            Label(statusText(call.status), Modifier.padding(start = 8.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        if (call.output.isNotBlank()) {
            BasicText(call.output.trimEnd(), style = LocalCodeStyleSmall(), maxLines = OUTPUT_LINES)
        }
        for (diff in call.diffs) DiffPreview(diff)
    }
}

private fun statusColor(status: AgentToolStatus) =
    when (status) {
        AgentToolStatus.Pending, AgentToolStatus.Running -> IdeColors.Accent
        AgentToolStatus.Completed -> IdeColors.GitAdded
        AgentToolStatus.Failed -> IdeColors.Error
    }

private fun statusText(status: AgentToolStatus) =
    when (status) {
        AgentToolStatus.Pending -> "pending"
        AgentToolStatus.Running -> "running…"
        AgentToolStatus.Completed -> "done"
        AgentToolStatus.Failed -> "failed"
    }

// The lines a change removes and adds, between the lines it keeps at both ends.
@Composable
private fun DiffPreview(diff: org.example.kotlineditor.model.AgentDiff) {
    val lines = changedLines(diff.oldText, diff.newText)
    Column(Modifier.fillMaxWidth().background(IdeColors.Input, RoundedCornerShape(4.dp)).padding(6.dp)) {
        Label(diff.path.substringAfterLast('/').substringAfterLast('\\'), fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        for ((sign, line) in lines.take(DIFF_LINES)) {
            val color = if (sign == '+') IdeColors.GitAdded else IdeColors.Error
            BasicText("$sign $line", style = LocalCodeStyleSmall().copy(color = color), maxLines = 1)
        }
        if (lines.size > DIFF_LINES) {
            Label("… ${lines.size - DIFF_LINES} more lines", color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

/** The removed (`-`) and added (`+`) lines of a change: what differs between the common first and last lines. */
internal fun changedLines(oldText: String?, newText: String): List<Pair<Char, String>> {
    val old = oldText?.lines().orEmpty()
    val new = newText.lines()
    var prefix = 0
    while (prefix < old.size && prefix < new.size && old[prefix] == new[prefix]) prefix++
    var suffix = 0
    val maxSuffix = minOf(old.size, new.size) - prefix
    while (suffix < maxSuffix && old[old.size - 1 - suffix] == new[new.size - 1 - suffix]) suffix++
    val removed = old.subList(prefix, old.size - suffix).map { '-' to it }
    val added = new.subList(prefix, new.size - suffix).map { '+' to it }
    return removed + added
}

@Composable
private fun PlanView(plan: AgentItem.Plan) {
    Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label("Plan", fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
        for (entry in plan.entries) {
            val mark =
                when (entry.status) {
                    "completed" -> "✓"
                    "in_progress" -> "▸"
                    else -> "○"
                }
            val color = if (entry.status == "completed") IdeColors.TextSecondary else IdeColors.Text
            Label("$mark  ${entry.content}", color = color, fontSize = 12.sp, maxLines = 2)
        }
    }
}

// The agent asks before it acts: one button per option while it waits, then the answer.
@Composable
private fun PermissionCard(request: AgentItem.Permission, onAnswer: (String) -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(IdeColors.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, if (request.pending) IdeColors.Accent else IdeColors.Separator, RoundedCornerShape(6.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("The agent asks to: ${request.title}", fontWeight = FontWeight.SemiBold, maxLines = 3)
        if (request.pending) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (option in request.options) {
                    IdeButton(
                        option.name,
                        { onAnswer(option.id) },
                        Modifier.testTag(AppTags.agentPermission(option.id)),
                        primary = option.kind.startsWith("allow"),
                    )
                }
            }
        } else {
            val chosen = request.options.firstOrNull { it.id == request.chosen }?.name
            val answer = if (request.cancelled) "Cancelled" else "Answered: $chosen"
            Label(answer, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
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
        val name = conversation.agentName ?: agent.agentLabel
        Label(name, Modifier.testTag(AppTags.AGENT_NAME), color = IdeColors.TextSecondary, fontSize = 12.sp)
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
