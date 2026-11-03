package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.workspaceProject

private val PANEL_WIDTH = 260.dp

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
            .testTag(AgentTags.AGENT)
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
            // The files and diffs of the worktree stay under their conversation, in its own row of sub-tabs.
            DetailStrip(conversation)
            Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
            val workspace = conversation.workspaceProject
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
