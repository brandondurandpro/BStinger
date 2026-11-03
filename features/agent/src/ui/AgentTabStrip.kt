package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.toggle

// The tabs of agent mode, one per open conversation, and the button of the strip that shows the conversation list.

@Composable
fun ConversationsButton(agent: AgentState) {
    val toggle = { agent.conversationsVisible = !agent.conversationsVisible }
    StripButton(agent.conversationsVisible, "Conversations", AgentTags.AGENT_STRIP_CONVERSATIONS, toggle) {
        SpeechBubbleIcon()
    }
    val separator = Modifier.padding(vertical = 6.dp).width(22.dp)
    Box(separator.height(1.dp).background(IdeColors.Separator))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StripButton(
    active: Boolean,
    tooltip: String,
    tag: String,
    onClick: () -> Unit,
    icon: @Composable () -> Unit,
) {
    TooltipArea(tooltip = { Tooltip(tooltip) }) {
        Box(
            Modifier
                .size(30.dp)
                .background(if (active) IdeColors.Selection else Color.Transparent, RoundedCornerShape(6.dp))
                .clickable(onClick = onClick)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

@Composable
fun AgentTabStrip(agent: AgentState) {
    val listState = rememberLazyListState()
    val active = agent.tabs.active
    LaunchedEffect(active) {
        val index = agent.tabs.open.indexOf(active)
        if (index >= 0 && !isFullyVisible(listState, index)) listState.animateScrollToItem(index)
    }
    LazyRow(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(IdeColors.Panel)
            .testTag(AgentTags.AGENT_TABS),
        state = listState,
    ) {
        items(agent.tabs.open, key = { it.id }) { conversation ->
            AgentTabView(agent, conversation, conversation == active)
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AgentTabView(agent: AgentState, conversation: Conversation, active: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val press =
        Modifier.onPointerEvent(PointerEventType.Press) { event ->
            onTabPress(event, { agent.open(conversation) }, { agent.tabs.close(conversation) }, {})
        }
    Row(
        Modifier
            .fillMaxHeight()
            .background(if (hovered && !active) IdeColors.Hover else Color.Transparent)
            .activeUnderline(active)
            .hoverable(interaction)
            .then(press)
            .testTag(AgentTags.agentTab(conversation.id))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatTabIcon(conversation.busy)
        Label(conversation.title, Modifier.widthIn(max = 220.dp).padding(start = 6.dp, end = 4.dp))
        // How many files and diffs are open inside, so none is forgotten behind another conversation.
        val editor = conversation.workspace?.editor
        val details = editor?.allTabs?.size ?: 0
        if (details > 0) DetailCount(details, Modifier.testTag(AgentTags.agentTabDetails(conversation.id)))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (hovered || active) {
                IconButton(
                    { agent.tabs.close(conversation) },
                    Modifier.testTag(AgentTags.agentTabClose(conversation.id)),
                ) { CloseIcon() }
            }
        }
    }
}

@Composable
private fun ChatTabIcon(busy: Boolean) {
    if (busy) RunningDot() else SpeechBubbleIcon(IdeColors.TextSecondary)
}

@Composable
fun NoTab(agent: AgentState) {
    Column(
        Modifier.fillMaxSize().testTag(AgentTags.AGENT_EMPTY),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Label("No conversation open", color = IdeColors.TextSecondary, fontSize = 14.sp)
        Spacer(Modifier.height(12.dp))
        IdeButton("New conversation", { agent.newConversation() }, primary = true)
        Spacer(Modifier.height(16.dp))
        Label("Ctrl+Alt+N  new conversation   ·   Ctrl+Alt+A  back to the editors", color = IdeColors.TextSecondary)
    }
}
