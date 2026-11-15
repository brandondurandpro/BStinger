package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.state.AgentRun
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.OrchestrationMode
import org.example.kotlineditor.state.agentRuns
import org.example.kotlineditor.state.followDelegation
import org.example.kotlineditor.state.unfollowDelegation
import org.example.kotlineditor.state.managerCalls
import org.example.kotlineditor.state.nestedCalls

private val DETAIL_WIDTH = 560.dp
private val DETAIL_MAX_HEIGHT = 420.dp
private const val MANAGER_KEY = "manager"
private const val MANAGER_MARK = "♛ "

/**
 * The agents of a conversation, top left: the manager (♛) first, then the sub-agents it launched, each with its
 * status; a click shows what it did. Nothing without a team and without a sub-agent run: the agent alone is no strip.
 */
@Composable
internal fun AgentStrip(agent: AgentState, conversation: Conversation) {
    val team = agent.teams.firstOrNull { it.name == conversation.team }
    val items = conversation.shownItems
    val runs = agentRuns(items, team)
    var shown by remember(conversation.id) { mutableStateOf<String?>(null) }
    if (team == null && runs.isEmpty()) return
    val managerName = team?.manager?.name ?: "Agent"
    val managerBusy = conversation.busy
    Row(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState())
            .testTag(AgentTags.AGENT_STRIP),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val managerStatus = if (managerBusy) AgentToolStatus.Running else AgentToolStatus.Completed
        StripChip(MANAGER_MARK + managerName, managerStatus, AgentTags.agentStripEntry(managerName)) { shown = MANAGER_KEY }
        if (agent.modeOf(conversation) == OrchestrationMode.Relay) {
            Label("relay", Modifier.testTag(AgentTags.AGENT_STRIP_RELAY), color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
        for (run in runs) StripChip(run.name, run.status, AgentTags.agentStripEntry(run.name)) { shown = run.id }
    }
    val key = shown ?: return
    val close = { shown = null }
    val run = runs.firstOrNull { it.id == key }
    if (key == MANAGER_KEY) {
        ManagerDetail(MANAGER_MARK + managerName, managerCalls(items, agent.modeOf(conversation) == OrchestrationMode.Relay), close)
    } else if (run != null && run.relayed) {
        RelayRunDetail(agent, run, close)
    } else if (run != null) {
        RunDetail(run, nestedCalls(items, run), close)
    }
}

@Composable
private fun StripChip(name: String, status: AgentToolStatus, tag: String, open: () -> Unit) {
    Row(
        Modifier
            .clip(IdeShapes.Medium)
            .border(1.dp, IdeColors.Separator, IdeShapes.Medium)
            .clickable(onClick = open)
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(statusColor(status), CircleShape))
        Label(name, Modifier.padding(start = 6.dp), fontSize = 12.sp, maxLines = 1)
    }
}

@Composable
private fun DetailDialog(title: String, status: AgentToolStatus, close: () -> Unit, body: LazyListScope.() -> Unit) {
    Dialog(onDismissRequest = close) {
        Column(
            Modifier
                .width(DETAIL_WIDTH)
                .background(IdeColors.Popup, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                .padding(14.dp)
                .testTag(AgentTags.AGENT_STRIP_DETAIL),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                Label(statusText(status), color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
            // Lazy: only the cards in view are composed, however many calls the agent made.
            LazyColumn(
                Modifier.heightIn(max = DETAIL_MAX_HEIGHT),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = body,
            )
            Row {
                Box(Modifier.weight(1f))
                IdeButton("Close", close, Modifier.testTag(AgentTags.AGENT_STRIP_DETAIL_CLOSE), primary = true)
            }
        }
    }
}

@Composable
private fun RunDetail(run: AgentRun, calls: List<AgentItem.ToolCall>, close: () -> Unit) {
    DetailDialog(run.name, run.status, close) {
        item(key = "task") { Section("Task", run.description) }
        if (calls.isEmpty()) {
            item(key = "calls") { Section("What it did", "No command recorded.") }
        } else {
            item(key = "calls") {
                Label("What it did (${calls.size})", color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
            items(calls, key = { it.id }) { call -> ToolCallCard(call) }
        }
        val report = run.result.ifEmpty { "No report yet." }
        item(key = "report") { Section("Report", report) }
    }
}

// A run of the IDE's relay: the transcript of the session of the member, followed while the dialog is open.
@Composable
private fun RelayRunDetail(agent: AgentState, run: AgentRun, close: () -> Unit) {
    val key = run.child
    if (key == null) {
        DetailDialog(run.name, run.status, close) { item(key = "starting") { Section("Task", "The member is starting.") } }
        return
    }
    val followed = remember(key) { agent.followDelegation(key, run.name) }
    DisposableEffect(key) { onDispose { agent.unfollowDelegation(key) } }
    val rows = transcriptRows(followed.shownItems)
    DetailDialog(run.name, run.status, close) {
        items(rows, key = { it.key }) { row ->
            when (row) {
                is TranscriptRow.Single -> ItemView(agent, followed, row.item)
                is TranscriptRow.Tools -> ToolCallRun(followed, row.calls)
            }
        }
        item(key = "report") { Section("Report", run.result.ifEmpty { "No report yet." }) }
    }
}

@Composable
private fun ManagerDetail(title: String, calls: List<AgentItem.ToolCall>, close: () -> Unit) {
    val working = if (calls.any { it.status == AgentToolStatus.Running }) AgentToolStatus.Running else AgentToolStatus.Completed
    DetailDialog(title, working, close) {
        if (calls.isEmpty()) {
            item(key = "none") { Label("No command yet.", color = IdeColors.TextSecondary, fontSize = 12.sp) }
        }
        items(calls, key = { it.id }) { call -> ToolCallCard(call) }
    }
}

@Composable
private fun Section(title: String, text: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Label(title, color = IdeColors.TextSecondary, fontSize = 12.sp)
        // The text color of the theme (a Dialog has no content color of its IDE), and every line of the report.
        Label(text, color = IdeColors.Text, maxLines = Int.MAX_VALUE)
    }
}
