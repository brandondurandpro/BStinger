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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.AgentOutcome
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.outcome
import org.example.kotlineditor.state.EditorState
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.toggle

// The tabs of agent mode, one per open conversation, and the button of the strip that shows the conversation list.

@Composable
fun ConversationsButton(agent: AgentState) {
    val toggle = { agent.conversationsVisible = !agent.conversationsVisible }
    StripButton(agent.conversationsVisible, "Conversations", AgentTags.AGENT_STRIP_CONVERSATIONS, toggle) {
        SpeechBubbleIcon(if (agent.conversationsVisible) IdeColors.Text else IdeColors.TextSecondary)
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
                .background(if (active) IdeColors.Selection else Color.Transparent, IdeShapes.Large)
                .clickable(onClick = onClick)
                .testTag(tag),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
    }
}

/** The conversations open, one tab each. */
@Composable
fun AgentTabStrip(agent: AgentState) {
    TabRow(agent, chatItems(agent), AgentTags.AGENT_TABS, height = 36.dp)
}

/**
 * Under the conversations: what belongs to the conversation shown, its files and diffs, each a tab, with a tab that
 * goes back to the conversation itself. Nothing is shown while no file or diff is open.
 */
@Composable
fun AgentDetailStrip(agent: AgentState) {
    val items = detailItems(agent)
    if (items.isEmpty()) return
    TabRow(agent, items, AgentTags.AGENT_DETAIL_TABS, height = 32.dp)
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

@Composable
private fun TabRow(agent: AgentState, items: List<StripItem>, tag: String, height: Dp) {
    val listState = rememberLazyListState()
    val active = agent.tabs.active
    val shown = items.indexOfFirst { it.isShown(active) }
    LaunchedEffect(shown) {
        if (shown >= 0 && !isFullyVisible(listState, shown)) listState.animateScrollToItem(shown)
    }
    val density = LocalDensity.current
    var contextMenu by remember { mutableStateOf<Pair<String, DpOffset>?>(null) }
    LazyRow(
        Modifier
            .fillMaxWidth()
            .height(height)
            .background(IdeColors.Panel)
            .wheelScrollsHorizontally(listState)
            .testTag(tag),
        state = listState,
    ) {
        items(items, key = { it.key }) { item ->
            val openMenu = { position: Offset ->
                contextMenu = item.key to with(density) { DpOffset(position.x.toDp(), position.y.toDp()) }
            }
            val menu =
                @Composable {
                    val asked = contextMenu?.takeIf { it.first == item.key }
                    if (asked != null) {
                        val actions = closeActionsOf(items, item)
                        val index = items.indexOf(item)
                        CloseTabsMenu(index, items.size, actions, asked.second, { contextMenu = null })
                    }
                }
            when (item) {
                is StripItem.Chat -> AgentTabView(agent, item.conversation, item.isShown(active), openMenu, menu)
                is StripItem.File -> FileTabView(item, openMenu, menu)
                is StripItem.Back -> BackTabView(item)
            }
        }
    }
}

// The tabs the menu of [item] closes: conversations are closed (not deleted), files and diffs are closed.
private fun closeActionsOf(items: List<StripItem>, item: StripItem): CloseTabsActions {
    val index = items.indexOf(item)
    val closeAll = { items.toList().forEach { it.close() } }
    return CloseTabsActions(
        close = item::close,
        closeOthers = { items.filter { it != item }.forEach { it.close() } },
        closeToLeft = { items.take(index).forEach { it.close() } },
        closeToRight = { items.drop(index + 1).forEach { it.close() } },
        closeAll = closeAll,
    )
}

// What the strip lists: each open conversation, then the files and diffs open in the worktree of the active one.
private sealed interface StripItem {
    val key: String

    fun isShown(active: Conversation?): Boolean

    fun close()

    class Chat(val agent: AgentState, val conversation: Conversation) : StripItem {
        override fun close() = agent.closeTab(conversation)

        override val key = "chat:${conversation.id}"

        // The conversation of the file or diff shown stays marked: the second row says which one it is.
        override fun isShown(active: Conversation?): Boolean = conversation == active
    }

    // The tab of the second row that shows the conversation itself.
    class Back(val conversation: Conversation) : StripItem {
        override fun close() = Unit

        override val key = "back:${conversation.id}"

        override fun isShown(active: Conversation?): Boolean = conversation == active && conversation.showsChat
    }

    class File(val conversation: Conversation, val editor: EditorState, val tab: EditorTab) : StripItem {
        override fun close() {
            editor.close(tab)
            if (editor.allTabs.isEmpty()) conversation.showsChat = true
        }

        override val key = "file:${conversation.id}:${tab.key}"

        override fun isShown(active: Conversation?): Boolean =
            conversation == active && !conversation.showsChat && editor.activeTab == tab
    }
}

private fun chatItems(agent: AgentState): List<StripItem> = agent.tabs.open.map { StripItem.Chat(agent, it) }

// The conversation shown, then the files and diffs open in its worktree; none when nothing is open there.
private fun detailItems(agent: AgentState): List<StripItem> {
    val conversation = agent.tabs.active ?: return emptyList()
    val editor = conversation.workspace?.editor ?: return emptyList()
    if (editor.allTabs.isEmpty()) return emptyList()
    return listOf<StripItem>(StripItem.Back(conversation)) + editor.allTabs.map { StripItem.File(conversation, editor, it) }
}

@Composable
private fun BackTabView(item: StripItem.Back) {
    val conversation = item.conversation
    val shown = item.isShown(conversation)
    Row(
        Modifier
            .fillMaxHeight()
            .activeUnderline(shown)
            .clickable { conversation.showsChat = true }
            .padding(horizontal = 10.dp)
            .testTag(AgentTags.agentDetailChat(conversation.id)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpeechBubbleIcon(IdeColors.TextSecondary)
        Label("Conversation", Modifier.padding(start = 6.dp))
    }
}

@Composable
private fun FileTabView(item: StripItem.File, onContextMenu: (Offset) -> Unit, menu: @Composable () -> Unit) {
    val conversation = item.conversation
    val editor = item.editor
    val tab = item.tab
    val select = {
        conversation.showsChat = false
        editor.activate(tab)
    }
    EditorTabView(tab, item.isShown(conversation), select, item::close, onContextMenu, menu)
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun AgentTabView(
    agent: AgentState,
    conversation: Conversation,
    active: Boolean,
    onContextMenu: (Offset) -> Unit,
    menu: @Composable () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val press =
        Modifier.onPointerEvent(PointerEventType.Press) { event ->
            val select = {
                conversation.showsChat = true
                agent.open(conversation)
            }
            onTabPress(event, select, { agent.closeTab(conversation) }, onContextMenu)
        }
    // The menu is a sibling of the tab, anchored on the whole tab rather than at the end of its content.
    Box(Modifier.fillMaxHeight()) {
        Row(
            Modifier
                .fillMaxHeight()
                .background(if (hovered && !active) IdeColors.Hover else Color.Transparent)
                .activeUnderline(active)
                .hoverable(interaction)
                .then(press)
                .testTag(AgentTags.agentTab(conversation.id))
                .semantics { selected = active }
                .padding(start = 10.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatTabIcon(conversation)
            Label(conversation.title, Modifier.widthIn(max = 220.dp).padding(start = 6.dp, end = 4.dp))
            // How many files and diffs are open inside another conversation, so none is forgotten behind this one.
            val editor = conversation.workspace?.editor
            val details = if (agent.tabs.active == conversation) 0 else editor?.allTabs?.size ?: 0
            if (details > 0) DetailCount(details, Modifier.testTag(AgentTags.agentTabDetails(conversation.id)))
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (hovered || active) {
                    IconButton(
                        { agent.closeTab(conversation) },
                        Modifier.testTag(AgentTags.agentTabClose(conversation.id)),
                    ) { CloseIcon() }
                }
            }
        }
        menu()
    }
}

@Composable
private fun ChatTabIcon(conversation: Conversation) {
    if (conversation.outcome == AgentOutcome.Quiet) {
        SpeechBubbleIcon(IdeColors.TextSecondary)
    } else {
        AgentStatus(conversation)
    }
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
