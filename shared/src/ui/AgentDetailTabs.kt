package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import org.example.kotlineditor.state.WorkMode

// Sub-tabs of a conversation: the conversation itself, then the editors of the project it works on (files and diffs
// opened from the Project and Git tool windows).

@Composable
internal fun DetailStrip(conversation: Conversation) {
    val workspace = conversation.workspace
    val editor = workspace?.editor
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            .background(IdeColors.Editor)
            .padding(horizontal = 6.dp)
            .testTag(Tags.AGENT_DETAILS),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DetailChip(
            selected = conversation.showsChat || editor == null,
            tag = Tags.AGENT_DETAIL_CONVERSATION,
            onSelect = { conversation.showsChat = true },
        ) {
            SpeechBubbleIcon(IdeColors.TextSecondary)
            Label("Conversation", Modifier.padding(start = 6.dp), fontSize = 12.sp)
        }
        val files = editor?.allTabs.orEmpty()
        if (files.isNotEmpty()) {
            val active = editor?.activeTab
            DetailChip(
                selected = !conversation.showsChat,
                tag = Tags.AGENT_DETAIL_EDITOR,
                onSelect = { conversation.showsChat = false },
            ) {
                FileIcon(active?.entry?.name.orEmpty(), isDirectory = false, size = 14.dp)
                Label(
                    active?.title ?: "Files",
                    Modifier.widthIn(max = 220.dp).padding(start = 6.dp, end = 6.dp),
                    fontSize = 12.sp,
                )
                DetailCount(files.size)
            }
        }
    }
}

/** Where the agent works, in words, and the switch between the current branch and a worktree of its own. */
@Composable
internal fun WorkspaceStatus(agent: AgentState, conversation: Conversation, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).padding(end = 10.dp).testTag(Tags.AGENT_WORKSPACE_STATUS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceText(conversation)
        }
        WorkModeSwitch(agent, conversation)
    }
}

@Composable
private fun WorkspaceText(conversation: Conversation) {
    val workspace = conversation.workspace
    val error = conversation.workspaceError
    val branch = conversation.currentBranch
    when {
        error != null -> {
            Label("No worktree: $error", color = IdeColors.Error, fontSize = 12.sp)
        }

        workspace == null || conversation.preparingWorktree -> {
            val text = "Preparing a worktree for ${conversation.branch}…"
            Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }

        else -> {
            if (branch != null) {
                BranchIcon()
                Label(branch, Modifier.padding(start = 6.dp, end = 8.dp), fontSize = 12.sp)
            }
            val text =
                if (conversation.mode == WorkMode.Direct) {
                    "Works directly on this branch"
                } else {
                    "Works in a worktree: ${workspace.root.path}"
                }
            Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

// Current branch | Worktree, like the IDE | Agent switch of the toolbar; locked while the agent answers.
@Composable
private fun WorkModeSwitch(agent: AgentState, conversation: Conversation) {
    Row(
        Modifier
            .background(IdeColors.Panel, RoundedCornerShape(6.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(6.dp))
            .padding(2.dp),
    ) {
        for ((mode, text, tag) in WORK_MODES) {
            ModeButton(text, conversation.mode == mode, tag) { agent.setMode(conversation, mode) }
        }
    }
}

private val WORK_MODES =
    listOf(
        Triple(WorkMode.Direct, "Current branch", Tags.AGENT_MODE_DIRECT),
        Triple(WorkMode.Worktree, "Worktree", Tags.AGENT_MODE_WORKTREE),
    )

// A rounded sub-tab.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DetailChip(
    selected: Boolean,
    tag: String,
    onSelect: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val background =
        when {
            selected -> IdeColors.Selection
            hovered -> IdeColors.Hover
            else -> Color.Transparent
        }
    val press =
        Modifier.onPointerEvent(PointerEventType.Press) { event ->
            onTabPress(event, onSelect, {}, {})
        }
    Row(
        Modifier
            .height(24.dp)
            .background(background, RoundedCornerShape(5.dp))
            .hoverable(interaction)
            .then(press)
            .testTag(tag)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        content()
    }
}

/** Number of files and diffs open in the worktree of a conversation, shown on its tab. */
@Composable
internal fun DetailCount(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(end = 2.dp)
            .background(IdeColors.Hover, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(count.toString(), color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
}
