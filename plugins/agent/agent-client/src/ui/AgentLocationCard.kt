package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.GitAction
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.state.ask

private const val SHORT_HASH = 7

/**
 * The "Informations" card at the top right: the branch the agent works on and whether it is a worktree of its own, the
 * branch it started from, the state of the directory in Git, its last commit, its folder (with a way to open it), and
 * what to ask the agent to do with the work: commit, push.
 */
@Composable
internal fun LocationCard(agent: AgentState, conversation: Conversation, modifier: Modifier) {
    val workspace = conversation.workspace ?: return
    Column(
        modifier
            .width(QUOTA_CARD_WIDTH)
            .background(IdeColors.Panel, IdeShapes.ExtraLarge)
            .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
            .padding(12.dp)
            .testTag(AgentTags.AGENT_LOCATION_CARD),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("Informations", color = IdeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        // Selectable, to copy the path or the name of a branch (Ctrl+C, or the menu of a right click).
        SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                BranchLine(conversation)
                BaseLine(conversation)
                GitStateLine(conversation)
                LastCommitLine(conversation)
                InfoLine("Path", workspace.root.path, AgentTags.AGENT_INFO_PATH)
            }
        }
        OpenButtons(conversation)
        GitActionsButton(agent, conversation)
    }
}

@Composable
private fun BranchLine(conversation: Conversation) {
    val worktree = conversation.mode == WorkMode.Worktree
    val name = if (worktree) conversation.branch else conversation.currentBranch
    val mode = if (worktree) "own worktree" else "in the project"
    Column(Modifier.testTag(AgentTags.AGENT_INFO_BRANCH)) {
        Row {
            Label("Branch", Modifier.padding(end = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Label(mode, Modifier.testTag(AgentTags.AGENT_INFO_MODE), color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
        if (name != null) InfoValue(name)
    }
}

// The branch a worktree started from: the choice made when the conversation began, which nothing else shows.
@Composable
private fun BaseLine(conversation: Conversation) {
    val base = conversation.base
    if (conversation.mode == WorkMode.Worktree && base != null) {
        InfoLine("Started from", base, AgentTags.AGENT_INFO_BASE)
    }
}

// Where the directory stands in Git: how far from its base, and what is not committed yet.
@Composable
private fun GitStateLine(conversation: Conversation) {
    val changes = conversation.changes
    val failure = changes.failure
    if (failure != null) {
        // Not the last figures: they may be out of date, and the card must not claim a clean directory.
        Label(failure, Modifier.testTag(AgentTags.AGENT_INFO_GIT_ERROR), color = IdeColors.Error, fontSize = 12.sp, maxLines = Int.MAX_VALUE)
        return
    }
    if (!changes.loaded) return
    val count = changes.files.size
    val uncommitted = if (count == 0) "clean" else if (count == 1) "1 uncommitted file" else "$count uncommitted files"
    val distance = changes.aheadBehind
    val text = if (distance == null) uncommitted else "${distanceText(distance.ahead, distance.behind)} · $uncommitted"
    InfoLine("Git", text, AgentTags.AGENT_INFO_GIT)
}

private fun distanceText(ahead: Int, behind: Int): String =
    when {
        ahead == 0 && behind == 0 -> "up to date"
        behind == 0 -> "$ahead ahead"
        ahead == 0 -> "$behind behind"
        else -> "$ahead ahead, $behind behind"
    }

// Only once the worktree has commits of its own: before, its last commit is the base's.
@Composable
private fun LastCommitLine(conversation: Conversation) {
    val commit = conversation.changes.lastCommit ?: return
    InfoLine("Last commit", "${commit.hash.take(SHORT_HASH)} ${commit.subject}", AgentTags.AGENT_INFO_COMMIT)
}

@Composable
private fun InfoLine(title: String, value: String, tag: String) {
    Column(Modifier.testTag(tag)) {
        Label(title, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        InfoValue(value)
    }
}

// The whole value, on as many lines as it takes: a path cut short says nothing.
@Composable
private fun InfoValue(value: String) {
    Label(value, color = IdeColors.TextSecondary, fontSize = 12.sp, maxLines = Int.MAX_VALUE)
}

// The folder in the tool windows of the conversation: its files, or a terminal that starts in it.
@Composable
private fun OpenButtons(conversation: Conversation) {
    val workspace = conversation.workspace ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        IdeButton("Files", { workspace.showToolWindow(ToolWindow.Project) }, Modifier.testTag(AgentTags.AGENT_INFO_FILES), compact = true)
        IdeButton("Terminal", { workspace.showToolWindow(ToolWindow.Terminal) }, Modifier.testTag(AgentTags.AGENT_INFO_TERMINAL), compact = true)
    }
}

/** "Commit ▾": the agent does what is chosen (commit, push, pull request) with its own tools, in its directory. */
@Composable
private fun GitActionsButton(agent: AgentState, conversation: Conversation) {
    var open by remember { mutableStateOf(false) }
    val enabled = !conversation.busy && conversation.workspaceError == null
    Box {
        IdeButton("Commit ▾", { open = true }, Modifier.testTag(AgentTags.AGENT_GIT_ACTIONS), enabled = enabled)
        IdeDropdownMenu(open, onDismissRequest = { open = false }) {
            for (action in GitAction.entries) {
                IdeMenuItem(
                    text = { Text(action.label, fontSize = 13.sp) },
                    onClick = {
                        open = false
                        agent.ask(conversation, action)
                    },
                    modifier = Modifier.testTag(AgentTags.agentGitAction(action.name)),
                )
            }
        }
    }
}
