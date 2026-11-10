package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.MergeHistory

private val HISTORY_HEIGHT = 180.dp
private val HISTORY_ROW = 22.dp

// The commits that changed the file on each side since the two sides diverged, loaded when the panel opens.
@Composable
internal fun MergeHistoryPanel(loadHistory: suspend () -> MergeHistory, onOpenCommit: (GitCommit) -> Unit) {
    var history by remember { mutableStateOf<Result<MergeHistory>?>(null) }
    LaunchedEffect(Unit) { history = runCatching { loadHistory() } }
    val loaded = history
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
    Row(Modifier.fillMaxWidth().height(HISTORY_HEIGHT).testTag(EditorTags.MERGE_HISTORY)) {
        val failure = loaded?.exceptionOrNull()
        val value = loaded?.getOrNull()
        when {
            failure != null -> {
                HistoryMessage("Cannot read the history: ${failure.message}")
            }

            value == null -> {
                HistoryMessage("Loading…")
            }

            else -> {
                HistorySide("ours", value.ours, onOpenCommit, Modifier.weight(1f))
                Box(Modifier.fillMaxHeight().width(1.dp).background(IdeColors.Border))
                HistorySide("theirs", value.theirs, onOpenCommit, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun HistoryMessage(text: String) {
    Label(text, Modifier.padding(12.dp), color = IdeColors.TextSecondary, fontSize = 12.sp)
}

@Composable
private fun HistorySide(side: String, commits: List<GitCommit>, onOpenCommit: (GitCommit) -> Unit, modifier: Modifier) {
    if (commits.isEmpty()) {
        Box(modifier.fillMaxHeight()) { HistoryMessage("No commit of this side changed the file") }
        return
    }
    LazyColumn(modifier.fillMaxHeight()) {
        items(commits, key = { it.hash }) { commit ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(HISTORY_ROW)
                    .testTag(EditorTags.mergeHistoryCommit(side, commit.hash))
                    .clickable { onOpenCommit(commit) }
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label(commit.shortHash, color = IdeColors.Link, fontSize = 11.sp)
                Label(commit.subject, Modifier.weight(1f).padding(start = 10.dp), fontSize = 12.sp)
                Label(
                    "${commit.author} · ${dateOf(commit.time)}",
                    Modifier.padding(start = 10.dp),
                    color = IdeColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

// `yyyy-MM-dd` of a commit time (seconds since the epoch), in the local time zone.
private fun dateOf(seconds: Long): String =
    java.time.Instant
        .ofEpochSecond(seconds)
        .atZone(java.time.ZoneId.systemDefault())
        .toLocalDate()
        .toString()
