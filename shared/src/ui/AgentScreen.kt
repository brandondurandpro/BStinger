package org.example.kotlineditor.ui

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.toggle

private val PANEL_WIDTH = 260.dp

/**
 * Agent mode: the strip of buttons on the far left (Conversations, then the tool windows), the conversation list,
 * and the open conversations in tabs. The tool windows (Project, Git, Terminal, Network) are those of the worktree of
 * the active conversation: they follow its branch when another tab is selected.
 */
@Composable
internal fun AgentWorkspace(app: AppState, project: ProjectState) {
    val agent = project.agent
    val workspace = agent.current?.workspace
    val drag = remember { ToolWindowDrag() }
    val findFocus = remember(workspace) { FocusRequester() }
    val shortcuts =
        remember(workspace) { workspace?.let { GlobalShortcuts(IdeContext(app, it, findFocus), ideOnly = true) } }
    // The project itself is already saved and synchronized by its screen; a worktree is synchronized here.
    workspace?.takeIf { it !== project }?.let { SyncOnWindowFocus(it) }
    // The IDE shortcuts (Ctrl+S, Ctrl+Shift+N, Alt+1…) act where the agent works; agent mode handles its own first.
    val keys = Modifier.onPreviewKeyEvent { event -> shortcuts?.handle(event) == true }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(Tags.AGENT)
            .then(keys)
            .onGloballyPositioned { drag.workspace = it.boundsInRoot() },
    ) {
        Row(Modifier.fillMaxSize()) {
            ToolStrip(workspace, app.devtools) { ConversationsButton(agent) }
            VerticalDivider(Modifier.fillMaxHeight())
            if (agent.conversationsVisible) {
                ConversationList(agent, Modifier.width(PANEL_WIDTH).fillMaxHeight())
                VerticalDivider(Modifier.fillMaxHeight())
            }
            // Keyed by worktree: the tree, terminal and Git panel of one branch never show in another.
            key(workspace) { AgentBody(app, agent, workspace, drag, findFocus, Modifier.weight(1f)) }
        }
        DropOverlay(drag)
        workspace?.let { WorkspacePopups(it) }
    }
}

@Composable
private fun AgentBody(
    app: AppState,
    agent: AgentState,
    workspace: ProjectState?,
    drag: ToolWindowDrag,
    findFocus: FocusRequester,
    modifier: Modifier,
) {
    Column(modifier.fillMaxHeight()) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            workspace?.let { ToolDock(it, Dock.Left, drag) }
            AgentTabsArea(app, agent, findFocus, Modifier.weight(1f).fillMaxHeight().background(IdeColors.Editor))
            workspace?.let { ToolDock(it, Dock.Right, drag) }
        }
        workspace?.let { ToolDock(it, Dock.Bottom, drag) }
    }
}

// Popups and dialogs of the worktree: Go to File, Find in Files, New File from the tree…
@Composable
private fun WorkspacePopups(workspace: ProjectState) {
    ProjectPopups(workspace) { closePopup(workspace) }
    workspace.fileDialog?.let { dialog ->
        FileDialogPopup(workspace, dialog, onClose = { workspace.fileDialog = null })
    }
}

@Composable
private fun ConversationsButton(agent: AgentState) {
    val toggle = { agent.conversationsVisible = !agent.conversationsVisible }
    StripButton(agent.conversationsVisible, "Conversations", Tags.AGENT_STRIP_CONVERSATIONS, toggle) {
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
internal fun SpeechBubbleIcon(color: Color = IdeColors.Text) {
    Canvas(Modifier.size(16.dp)) {
        val stroke = 1.4.dp.toPx()
        val bubble = Size(size.width, size.height * 0.72f)
        drawRoundRect(color, size = bubble, cornerRadius = CornerRadius(3.dp.toPx()), style = Stroke(stroke))
        val tail =
            Path().apply {
                moveTo(size.width * 0.25f, bubble.height)
                lineTo(size.width * 0.22f, size.height)
                lineTo(size.width * 0.5f, bubble.height)
            }
        drawPath(tail, color, style = Stroke(stroke))
    }
}

@Composable
private fun AgentTabsArea(app: AppState, agent: AgentState, findFocus: FocusRequester, modifier: Modifier) {
    Column(modifier) {
        val conversation = agent.tabs.active
        if (conversation == null) {
            NoTab(agent)
        } else {
            AgentTabStrip(agent)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            // The files and diffs of the worktree stay under their conversation, in its own row of sub-tabs.
            DetailStrip(conversation)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            val workspace = conversation.workspace
            key(conversation.id) {
                if (workspace == null || conversation.showsChat) {
                    ConversationView(agent, conversation)
                } else {
                    EditorArea(app, workspace, findFocus, Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Composable
private fun AgentTabStrip(agent: AgentState) {
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
            .testTag(Tags.AGENT_TABS),
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
            .testTag(Tags.agentTab(conversation.id))
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatTabIcon(conversation.busy)
        Label(conversation.title, Modifier.widthIn(max = 220.dp).padding(start = 6.dp, end = 4.dp))
        // How many files and diffs are open inside, so none is forgotten behind another conversation.
        val editor = conversation.workspace?.editor
        val details = editor?.allTabs?.size ?: 0
        if (details > 0) DetailCount(details, Modifier.testTag(Tags.agentTabDetails(conversation.id)))
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            if (hovered || active) {
                IconButton(
                    { agent.tabs.close(conversation) },
                    Modifier.testTag(Tags.agentTabClose(conversation.id)),
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
private fun NoTab(agent: AgentState) {
    Column(
        Modifier.fillMaxSize().testTag(Tags.AGENT_EMPTY),
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
