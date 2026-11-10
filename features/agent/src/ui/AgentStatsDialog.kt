package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import org.example.kotlineditor.model.AgentToolKind
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.ConversationStats
import org.example.kotlineditor.state.durationText
import org.example.kotlineditor.state.statsOf
import java.util.Locale

private val DIALOG_WIDTH = 460.dp
private const val REFRESH_MILLIS = 1_000L

// "Statistics": the link that opens the figures of the conversation.
@Composable
internal fun StatsLink(conversation: Conversation, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    Label(
        "Statistics",
        modifier.clickable { open = true }.testTag(AgentTags.AGENT_STATS),
        color = IdeColors.Accent,
        fontSize = 12.sp,
    )
    if (open) StatsDialog(conversation) { open = false }
}

@Composable
private fun StatsDialog(conversation: Conversation, close: () -> Unit) {
    // The clock of a turn in progress moves: the figures are read again each second.
    var stats by remember { mutableStateOf(statsOf(conversation)) }
    LaunchedEffect(conversation) {
        while (true) {
            stats = statsOf(conversation)
            delay(REFRESH_MILLIS)
        }
    }
    Dialog(onDismissRequest = close) {
        Column(
            Modifier
                .width(DIALOG_WIDTH)
                .background(IdeColors.Popup, IdeShapes.ExtraLarge)
                .border(1.dp, IdeColors.Separator, IdeShapes.ExtraLarge)
                .padding(16.dp)
                .testTag(AgentTags.AGENT_STATS_DIALOG),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Label(conversation.title, fontWeight = FontWeight.SemiBold)
            Section("Time")
            Figure("Time worked", durationText(stats.workedMillis), AgentTags.AGENT_STATS_TIME)
            stats.averageTurnMillis?.let { Figure("Average turn", durationText(it)) }
            if (stats.longestTurnMillis > 0) Figure("Longest turn", durationText(stats.longestTurnMillis))
            Section("Cost")
            Figure("Cost", stats.cost?.let { String.format(Locale.ROOT, "$%.2f", it) } ?: "not reported", AgentTags.AGENT_STATS_COST)
            stats.usage?.let { Figure("Context", it) }
            Section("Activity")
            Figure("Prompts", stats.prompts.toString(), AgentTags.AGENT_STATS_PROMPTS)
            Figure("Answers", stats.answers.toString())
            ActionFigures(stats)
            Figure("Files changed", stats.filesChanged.toString())
            Figure("Uncommitted lines", "+${stats.linesAdded} −${stats.linesRemoved}")
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                IdeButton("Close", close, Modifier.testTag(AgentTags.AGENT_STATS_CLOSE))
            }
        }
    }
}

@Composable
private fun ActionFigures(stats: ConversationStats) {
    val total = stats.actions.values.sum()
    Figure("Actions", if (stats.failedActions > 0) "$total (${stats.failedActions} failed)" else total.toString())
    for ((kind, count) in stats.actions.entries.sortedByDescending { it.value }) {
        Figure("  ${kindName(kind)}", count.toString())
    }
}

private fun kindName(kind: AgentToolKind): String =
    when (kind) {
        AgentToolKind.Read -> "Reads"
        AgentToolKind.Edit -> "Edits"
        AgentToolKind.Delete -> "Deletions"
        AgentToolKind.Move -> "Moves"
        AgentToolKind.Search -> "Searches"
        AgentToolKind.Execute -> "Commands"
        AgentToolKind.Think -> "Thoughts"
        AgentToolKind.Fetch -> "Fetches"
        AgentToolKind.Other -> "Other"
    }

@Composable
private fun Section(title: String) {
    Label(title, Modifier.padding(top = 8.dp), color = IdeColors.TextSecondary, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun Figure(name: String, value: String, tag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(name, Modifier.weight(1f))
        val shown = if (tag == null) Modifier else Modifier.testTag(tag)
        Label(value, shown, fontWeight = FontWeight.Medium)
    }
}
