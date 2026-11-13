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

/** Where the agent works, in words, and the switch between the current branch and a worktree of its own. */
@Composable
internal fun WorkspaceStatus(agent: AgentState, conversation: Conversation, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).padding(end = 10.dp).testTag(AgentTags.AGENT_WORKSPACE_STATUS),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WorkspaceText(conversation)
        }
        // The place is chosen before the first prompt: the session of the agent works in one directory.
        if (!conversation.started) WorkModeSwitch(agent, conversation)
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
                    "Works in a worktree"
                }
            Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

// Current branch | Worktree, like the IDE | Agent switch of the toolbar; only before the conversation starts.
@Composable
private fun WorkModeSwitch(agent: AgentState, conversation: Conversation) {
    Row(
        Modifier
            .background(IdeColors.Panel, IdeShapes.Large)
            .border(1.dp, IdeColors.Separator, IdeShapes.Large)
            .padding(2.dp),
    ) {
        for ((mode, text, tag) in WORK_MODES) {
            ModeButton(text, conversation.mode == mode, tag) { agent.setMode(conversation, mode) }
        }
    }
}

private val WORK_MODES =
    listOf(
        Triple(WorkMode.Direct, "Current branch", AgentTags.AGENT_MODE_DIRECT),
        Triple(WorkMode.Worktree, "Worktree", AgentTags.AGENT_MODE_WORKTREE),
    )

/** Number of files and diffs open in the worktree of a conversation, shown on its tab. */
@Composable
internal fun DetailCount(count: Int, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(end = 2.dp)
            .background(IdeColors.Hover, IdeShapes.ExtraLarge)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(count.toString(), color = IdeColors.TextSecondary, fontSize = 11.sp)
    }
}
