package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.model.AgentTurnState
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Completing
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.drop
import org.example.kotlineditor.state.isLoginNotice
import org.example.kotlineditor.state.recallPrompt
import org.example.kotlineditor.state.usageText

private val CONTENT_WIDTH = 760.dp

// Far enough to reach the end of the last item, however long it grew.
private const val TO_THE_END = 100_000f

// One conversation of agent mode: its transcript (messages, tool calls, plans, permission requests) and the prompt.

@Composable
fun ConversationView(agent: AgentState, conversation: Conversation) {
    FollowTheEnd(conversation)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // The card of the quota is at the right of the transcript, which makes room for it when the window is narrow.
        val hasFigures =
            conversation.quota.isNotEmpty() || conversation.usage != null || conversation.started ||
                conversation.changes.files.isNotEmpty() || conversation.workspace != null
        val showsCard = maxWidth >= QUOTA_CARD_MIN_WIDTH && hasFigures
        val reserve = if (showsCard) (QUOTA_CARD_ROOM * 2 - (maxWidth - CONTENT_WIDTH)).coerceAtLeast(0.dp) else 0.dp
        CompositionLocalProvider(LocalQuotaCard provides showsCard) {
            ConversationColumn(agent, conversation, showsCard, reserve)
        }
    }
}

@Composable
private fun ConversationColumn(agent: AgentState, conversation: Conversation, showsCard: Boolean, reserve: Dp) {
    val listState = conversation.listState
    val items = conversation.shownItems
    Column(Modifier.fillMaxSize().testTag(AgentTags.agentView(conversation.id))) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (items.isEmpty()) {
                Label(
                    "Ask the agent about this project.",
                    Modifier.align(Alignment.Center),
                    color = IdeColors.TextSecondary,
                )
            }
            Box(Modifier.fillMaxSize().padding(end = reserve)) {
                SelectionContainer { Transcript(agent, conversation) }
            }
            if (showsCard) {
                // The quota, then what the agent changed, in a column of cards at the right of the transcript.
                Column(
                    Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 20.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LocationCard(agent, conversation, Modifier)
                    QuotaCard(conversation, Modifier)
                    ChangesCard(agent, conversation, Modifier)
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        // The card at the right shows the changes of a wide conversation; the bar is for a narrow one.
        if (!showsCard) ChangesBar(agent, conversation)
        Prompt(agent, conversation)
    }
}

// A new item, or the last one growing as it streams, scrolls to the bottom.
@Composable
private fun FollowTheEnd(conversation: Conversation) {
    val listState = conversation.listState
    val items = conversation.shownItems
    val last = items.lastOrNull()
    val rows = transcriptRows(items)
    LaunchedEffect(items.size, last?.version) {
        if (rows.isNotEmpty()) {
            listState.scrollToItem(rows.lastIndex)
            listState.scrollBy(TO_THE_END)
        }
    }
}

// The items, virtualized, and what the agent does while nothing shows yet.
@Composable
private fun Transcript(agent: AgentState, conversation: Conversation) {
    val listState = conversation.listState
    val rows = transcriptRows(conversation.shownItems)
    LazyColumn(
        Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 16.dp),
    ) {
        itemsIndexed(rows, key = { _, row -> row.key }) { index, row ->
            Box(
                Modifier
                    .widthIn(max = CONTENT_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp)
                    .testTag(AgentTags.agentMessage(index)),
            ) {
                when (row) {
                    is TranscriptRow.Single -> ItemView(agent, conversation, row.item)
                    is TranscriptRow.Tools -> ToolCallRun(conversation, row.calls)
                }
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
    if (!conversation.busy || conversation.probeFrom != null) return false
    return when (val last = conversation.shownItems.lastOrNull()) {
        is AgentItem.AgentText -> false
        is AgentItem.ToolCall -> last.status != AgentToolStatus.Running
        is AgentItem.Permission -> !last.pending
        else -> true
    }
}

internal val bodyStyle get() = TextStyle(fontFamily = IdeFonts.ui, color = IdeColors.Text, fontSize = 13.sp, lineHeight = 20.sp)

@Composable
private fun ItemView(agent: AgentState, conversation: Conversation, item: AgentItem) {
    when (item) {
        is AgentItem.UserText -> UserBubble(item.text, item.context)
        is AgentItem.AgentText -> MarkdownText(item.text.trimEnd(), Modifier.fillMaxWidth())
        is AgentItem.Thought -> ThoughtView(item.text)
        is AgentItem.ToolCall -> ToolCallCard(item)
        is AgentItem.Plan -> PlanView(item)
        is AgentItem.Permission -> PermissionCard(item) { option -> agent.answer(conversation, item, option) }
        is AgentItem.Notice -> NoticeWithLogin(agent, conversation, item)
    }
}

// A notice, with the ways to log in when the agent asked for it and nothing came after.
@Composable
private fun NoticeWithLogin(agent: AgentState, conversation: Conversation, notice: AgentItem.Notice) {
    // In a column: an item of the list stacks what it composes, the buttons would cover the text.
    Column(Modifier.fillMaxWidth()) {
        NoticeView(notice)
        val after = conversation.items.dropWhile { it.id != notice.id }.drop(1)
        val onlyNotices = after.all { it is AgentItem.Notice && !isLoginNotice(it) }
        if (isLoginNotice(notice) && onlyNotices) LoginBar(agent, conversation)
    }
}

@Composable
internal fun LocalCodeStyleSmall(): TextStyle =
    LocalCodeStyle.current.copy(color = IdeColors.Code, fontSize = 12.sp, lineHeight = 18.sp)

// Enter sends, Shift+Enter starts a new line, like most chat inputs.
@Composable
private fun Prompt(agent: AgentState, conversation: Conversation) {
    val focus = remember { FocusRequester() }
    val context = conversation.context
    LaunchedEffect(context.focusRequests) { runCatching { focus.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Where the agent works (the current branch or a worktree), just above what is sent to it.
        val width = Modifier.widthIn(max = CONTENT_WIDTH).fillMaxWidth()
        val commands = { conversation.commands.ifEmpty { agent.options.commands } }
        val files = remember(conversation) { FileSuggestions() }
        val completion = remember(conversation) { PromptCompletion(conversation, commands, files::of) }
        // A `/` typed before the agent listed its commands asks for them again.
        val wantsCommands = completion.completing is Completing.Command && commands().isEmpty()
        LaunchedEffect(wantsCommands) { if (wantsCommands) agent.options.refreshCommands(conversation) }
        val index = (conversation.workspace ?: agent.project).fileIndex
        SearchesFiles(files, completion.completing as? Completing.File, index, context)
        CompletionList(completion, width.padding(bottom = 6.dp))
        WorkspaceStatus(agent, conversation, width.padding(horizontal = 4.dp, vertical = 6.dp))
        // Above the input, not in its row of buttons: a narrow window must never push Stop and Send out of sight.
        Row(width.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            val usage = conversation.usage
            // In the card, the context and the cost are not said twice.
            val shown = if (LocalQuotaCard.current) "" else usage?.let(::usageText).orEmpty()
            Label(shown, Modifier.weight(1f).testTag(AgentTags.AGENT_USAGE), color = IdeColors.TextSecondary, fontSize = 12.sp)
            if (conversation.started && !LocalQuotaCard.current) StatsLink(conversation)
        }
        QuotaBar(agent, conversation, width.padding(horizontal = 4.dp, vertical = 2.dp))
        val workspaceRoot = (conversation.workspace ?: agent.project).root.path
        Column(
            width
                .droppableFiles { files -> files.forEach { context.drop(it, workspaceRoot) } }
                .background(IdeColors.Input, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.InputBorder, IdeShapes.ExtraLarge)
                .padding(10.dp),
        ) {
            ContextChips(context, Modifier.fillMaxWidth().padding(bottom = 6.dp))
            Box(Modifier.fillMaxWidth().heightIn(min = 40.dp, max = 200.dp)) {
                if (conversation.draft.isEmpty()) {
                    Label("Ask the agent… (Enter to send, Shift+Enter for a new line)", color = IdeColors.TextSecondary)
                }
                BasicTextField(
                    conversation.draftValue,
                    { conversation.draftValue = it },
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .testTag(AgentTags.AGENT_PROMPT)
                        .onPreviewKeyEvent { event ->
                            // The list of suggestions first: Enter chooses one there, and sends otherwise.
                            val handled = completion.handle(event) || pastesImage(event, context)
                            handled || recallsPrompt(event, conversation) || sendsOnEnter(event) { agent.send(conversation) }
                        },
                    textStyle = bodyStyle,
                    cursorBrush = SolidColor(IdeColors.Caret),
                )
            }
            PromptActions(agent, conversation)
        }
    }
}

// Up and Down walk through the prompts already sent while the draft is empty.
private fun recallsPrompt(event: KeyEvent, conversation: Conversation): Boolean {
    val older = event.key == Key.DirectionUp
    val newer = event.key == Key.DirectionDown
    val plain = !event.isShiftPressed && !event.isAltPressed && !event.isCtrlPressed
    val wanted = (older || newer) && plain && event.type == KeyEventType.KeyDown
    return wanted && conversation.recallPrompt(older)
}

private fun sendsOnEnter(event: KeyEvent, send: () -> Unit): Boolean {
    val enter = event.key == Key.Enter && !event.isShiftPressed
    if (enter && event.type == KeyEventType.KeyDown) send()
    return enter
}

@Composable
private fun PromptActions(agent: AgentState, conversation: Conversation) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        // The agent first (an id, not a whole command line), then the settings of its session.
        AgentPicker(agent, conversation)
        ModelPicker(agent, conversation)
        EffortPicker(agent, conversation)
        PermissionsPicker(agent, conversation)
        DiscussButton(agent, conversation)
        // The card of the changes has the button once something changed; before, it stays here.
        if (conversation.changes.files.isEmpty()) ReviewButton(agent, conversation)
        SendReviewButton(agent, conversation)
        Spacer(Modifier.width(8.dp))
        // The picker already says which agent answers: no label for it, only the room that keeps Stop and Send right.
        Spacer(Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // The silent question about the quota is no work to stop: the prompt keeps its Send button.
        if (conversation.busy && conversation.probeFrom == null) {
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
