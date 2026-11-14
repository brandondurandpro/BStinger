package org.example.kotlineditor.ui

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.workspaceProject

private const val PANEL_HEIGHT = 560f
private val THIN_SCROLLBAR = 4.dp
private const val LIST_KEY = "conversations"

/**
 * Agent mode: the strip of buttons on the far left (Conversations, then the tool windows), the conversation list,
 * and the open conversations in tabs. The tool windows (Project, Git, Terminal, Network) are those of the worktree of
 * the active conversation: they follow its branch when another tab is selected.
 */
@Composable
internal fun AgentWorkspace(app: AppState, project: ProjectState) {
    val agent = project.agent
    val workspace = agent.current?.workspaceProject
    val drag = remember { ToolWindowDrag() }
    val heights = remember { PanelHeights(PANEL_HEIGHT) }
    val findFocus = remember(workspace) { FocusRequester() }
    val shortcuts =
        remember(workspace) { workspace?.let { GlobalShortcuts(IdeContext(app, it, findFocus), ideOnly = true) } }
    // The project itself is already saved and synchronized by its screen; a worktree is synchronized here.
    workspace?.takeIf { it !== project }?.let { SyncOnWindowFocus(it) }
    // The IDE shortcuts (Ctrl+S, Ctrl+Shift+N, Alt+1…) act where the agent works; agent mode handles its own first.
    NotifyWhenFinished(project)
    val keys = Modifier.onPreviewKeyEvent { event -> shortcuts?.handle(event) == true }
    Box(
        Modifier
            .fillMaxSize()
            .testTag(AgentTags.AGENT)
            .then(keys)
            .onGloballyPositioned { drag.workspace = it.boundsInRoot() },
    ) {
        Row(Modifier.fillMaxSize()) {
            ToolStrip(workspace, app.devtools, onSettings = app.settings::open) { ConversationsButton(agent) }
            if (agent.conversationsVisible) {
                // The tree and the Git panel sit under the list; the column scrolls, each panel keeping its height.
                val column = Modifier.width(agent.conversationsWidth.dp).fillMaxHeight().padding(bottom = ISLAND_GAP)
                val scroll = rememberScrollState()
                Box(column) {
                    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
                        CollapsibleCard(
                            heights,
                            LIST_KEY,
                            AgentTags.AGENT_LIST_BAR,
                            AgentTags.AGENT_LIST_HEIGHT_SPLITTER,
                        ) { modifier -> ConversationList(agent, modifier.island()) }
                        workspace?.let { ToolDockBelow(it, drag, heights) }
                    }
                    ThinScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                }
                // Dragging to the right widens the list, on the left of the splitter.
                val density = LocalDensity.current
                SplitterBox(horizontalBar = false, tag = AgentTags.AGENT_LIST_SPLITTER) { delta ->
                    agent.resizeConversations(with(density) { delta.toDp().value })
                }
            }
            // Keyed by worktree: the tree, terminal and Git panel of one branch never show in another.
            key(workspace) { AgentBody(app, agent, workspace, drag, findFocus, Modifier.weight(1f)) }
        }
        DropOverlay(drag)
        workspace?.let { WorkspacePopups(it) }
        // Shown once the branches are read: a project that is no repository starts its conversation without asking.
        agent.basePicker?.takeIf { it.choices != null || it.failure != null }?.let { BasePickerPopup(agent, it) }
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
    Column(modifier.fillMaxHeight().padding(end = ISLAND_GAP, bottom = ISLAND_GAP)) {
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // With the conversation list shown, the left dock is under it instead.
            if (!agent.conversationsVisible) workspace?.let { ToolDock(it, Dock.Left, drag) }
            AgentTabsArea(app, agent, findFocus, Modifier.weight(1f).fillMaxHeight().island(IdeColors.Editor))
            workspace?.let { ToolDock(it, Dock.Right, drag) }
        }
        workspace?.let { ToolDock(it, Dock.Bottom, drag) }
    }
}

// Popups and dialogs of the worktree: Go to File, Find in Files, New File from the tree…
@Composable
private fun WorkspacePopups(workspace: ProjectState) {
    ProjectPopups(workspace) { closePopup(workspace) }
    if (workspace.git.branchPickerOpen) BranchPicker(workspace)
    workspace.fileDialog?.let { dialog ->
        FileDialogPopup(workspace, dialog, onClose = { workspace.fileDialog = null })
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
            AgentDetailStrip(agent)
            val workspace = conversation.workspaceProject
            key(conversation.id) {
                if (workspace == null || conversation.showsChat) {
                    ConversationView(agent, conversation)
                } else {
                    EditorArea(app, workspace, findFocus, Modifier.fillMaxSize(), showTabs = false, islands = false)
                }
            }
        }
    }
}

// A desktop notification when an agent finishes while the window is in the background.
@Composable
private fun NotifyWhenFinished(project: ProjectState) {
    val focused = LocalWindowInfo.current.isWindowFocused
    val inBackground by rememberUpdatedState(!focused)
    DisposableEffect(project) {
        project.finishedListener = { title, failed ->
            val text = if (failed) "The agent failed." else "The agent finished its work."
            if (inBackground) DesktopNotifications.show(title, text, failed)
        }
        project.askingListener = { title, question ->
            if (inBackground) DesktopNotifications.show(title, "The agent asks: $question", error = false)
        }
        onDispose {
            project.finishedListener = null
            project.askingListener = null
        }
    }
}

// A scrollbar thinner than the theme's, over the right edge of the column it belongs to.
@Composable
private fun ThinScrollbar(state: ScrollState, modifier: Modifier) {
    val style = LocalScrollbarStyle.current.copy(thickness = THIN_SCROLLBAR)
    CompositionLocalProvider(LocalScrollbarStyle provides style) {
        VerticalScrollbar(rememberScrollbarAdapter(state), modifier.testTag(AgentTags.AGENT_COLUMN_SCROLLBAR))
    }
}
