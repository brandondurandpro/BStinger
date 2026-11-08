package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.ChangedFile
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.openChange
import org.example.kotlineditor.state.refreshChanges
import org.example.kotlineditor.state.revertChanges

private val BAR_WIDTH = 760.dp
private val LIST_MAX_HEIGHT = 220.dp

/**
 * What the agent changed where it works, above the prompt: the number of files and of lines, then (opened) each file,
 * whose diff a click shows and whose changes can be thrown away. Hidden while nothing is changed.
 */
@Composable
fun ChangesBar(agent: AgentState, conversation: Conversation) {
    val changes = conversation.changes
    val failure = changes.failure
    if (changes.files.isEmpty() && failure == null) return
    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier
                .widthIn(max = BAR_WIDTH)
                .fillMaxWidth()
                .background(IdeColors.Panel, RoundedCornerShape(8.dp))
                .testTag(AgentTags.agentChanges(conversation.id)),
        ) {
            ChangesHeader(agent, conversation)
            if (failure != null) Label(failure, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = IdeColors.Error, fontSize = 12.sp)
            if (changes.expanded) ChangesList(agent, conversation)
        }
    }
}

@Composable
private fun ChangesHeader(agent: AgentState, conversation: Conversation) {
    val changes = conversation.changes
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { changes.expanded = !changes.expanded }
            .testTag(AgentTags.AGENT_CHANGES_TOGGLE)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(changes.expanded)
        val count = changes.files.size
        Label(if (count == 1) "1 file changed" else "$count files changed", Modifier.padding(start = 4.dp), fontWeight = FontWeight.Medium)
        Label("+${changes.added}", Modifier.padding(start = 10.dp), color = IdeColors.GitAdded, fontSize = 12.sp)
        Label("−${changes.removed}", Modifier.padding(start = 6.dp), color = IdeColors.GitDeleted, fontSize = 12.sp)
        Box(Modifier.weight(1f))
        IconButton({ agent.refreshChanges(conversation) }, Modifier.testTag(AgentTags.AGENT_CHANGES_REFRESH)) {
            Label("⟳", color = IdeColors.TextSecondary)
        }
        if (changes.files.isNotEmpty()) {
            ConfirmButton("Revert all", AgentTags.AGENT_CHANGES_REVERT_ALL) {
                agent.revertChanges(conversation, changes.files.map { it.path })
            }
        }
    }
}

@Composable
private fun ChangesList(agent: AgentState, conversation: Conversation) {
    Column(Modifier.fillMaxWidth().heightIn(max = LIST_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
        for (file in conversation.changes.files) {
            key(file.path) { ChangeRow(agent, conversation, file) }
        }
    }
}

@Composable
private fun ChangeRow(agent: AgentState, conversation: Conversation, file: ChangedFile) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .clickable { agent.openChange(conversation, file) }
                .testTag(AgentTags.agentChange(file.path))
                .padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(statusLetter(file.status), Modifier.width(16.dp), color = gitColor(file.status), fontWeight = FontWeight.Bold, fontSize = 12.sp)
            FileIcon(file.name, isDirectory = false)
            Label(file.name, Modifier.padding(start = 6.dp), color = gitColor(file.status))
            if (file.directory.isNotEmpty()) {
                Label(file.directory, Modifier.weight(1f).padding(start = 6.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
            } else {
                Box(Modifier.weight(1f))
            }
            Label("+${file.added}", Modifier.padding(start = 6.dp), color = IdeColors.GitAdded, fontSize = 11.sp)
            Label("−${file.removed}", Modifier.padding(start = 4.dp, end = 8.dp), color = IdeColors.GitDeleted, fontSize = 11.sp)
        }
        ConfirmButton("Revert", AgentTags.agentChangeRevert(file.path)) { agent.revertChanges(conversation, listOf(file.path)) }
    }
}

private fun statusLetter(status: GitFileStatus): String =
    when (status) {
        GitFileStatus.Modified -> "M"
        GitFileStatus.Added -> "A"
        GitFileStatus.Untracked -> "U"
        GitFileStatus.Deleted -> "D"
        GitFileStatus.Renamed -> "R"
    }
