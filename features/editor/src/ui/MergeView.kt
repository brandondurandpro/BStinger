package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.MergeHistory
import org.example.kotlineditor.state.MergeChoice
import org.example.kotlineditor.state.MergeKind
import org.example.kotlineditor.state.MergeRow
import org.example.kotlineditor.state.MergeState

private const val COLUMN_OURS = 0
private const val COLUMN_RESULT = 1
private const val COLUMN_THEIRS = 2

/** The choices a changed stretch offers; a stretch both sides changed the same way has no "Both" (it would repeat). */
private fun choicesOf(kind: MergeKind): List<MergeChoice> =
    if (kind == MergeKind.BothSame) {
        listOf(MergeChoice.Ours, MergeChoice.Base)
    } else {
        listOf(MergeChoice.Ours, MergeChoice.Theirs, MergeChoice.Both, MergeChoice.Base)
    }

/**
 * Three-way merge of a conflicted file: the current branch on the left, the result in the middle, the branch being
 * merged in on the right, aligned stretch by stretch. What both sides agree on is resolved already; for each stretch
 * that changed, the user takes a side, both, or the base, and the result follows. [onApply] writes it, once no
 * conflict is left.
 */
@Composable
fun MergeView(
    state: MergeState,
    listState: LazyListState,
    onApply: () -> Unit,
    onCancel: () -> Unit,
    loadHistory: suspend () -> MergeHistory,
    onOpenCommit: (GitCommit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rows by remember(state) { derivedStateOf { state.rows() } }
    val style = LocalCodeStyle.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(EditorTags.MERGE)) {
        MergeToolbar(
            state,
            onNext = {
                val current = rows.getOrNull(listState.firstVisibleItemIndex)?.chunk ?: -1
                val target = state.nextUnresolved(current)
                val position = rows.indexOfFirst { it.heading && it.chunk == target }
                if (position >= 0) scope.launch { listState.scrollToItem(position) }
            },
            onApply = onApply,
            onCancel = onCancel,
        )
        ColumnTitles(state)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize().testTag(EditorTags.MERGE_LIST), state = listState) {
                itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
                    if (row.heading) {
                        MergeHeading(state, row.chunk)
                    } else {
                        MergeLine(state, index, row, style)
                    }
                }
            }
            VerticalScrollbar(
                rememberScrollbarAdapter(listState),
                Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            )
        }
        if (state.historyVisible) MergeHistoryPanel(loadHistory, onOpenCommit)
    }
}

@Composable
private fun MergeToolbar(state: MergeState, onNext: () -> Unit, onApply: () -> Unit, onCancel: () -> Unit) {
    val unresolved = state.unresolved
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .testTag(EditorTags.MERGE_TOOLBAR)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val summary =
            when (unresolved) {
                0 -> "All conflicts resolved"
                1 -> "1 conflict left"
                else -> "$unresolved conflicts left"
            }
        Label(
            summary,
            Modifier.testTag(EditorTags.MERGE_STATUS),
            color = if (unresolved == 0) IdeColors.GitAdded else IdeColors.GitBehind,
            fontSize = 12.sp,
        )
        Spacer(Modifier.width(8.dp))
        MergeAction("Next", EditorTags.MERGE_NEXT, enabled = unresolved > 0, onClick = onNext)
        MergeAction(
            "All ours",
            EditorTags.MERGE_ALL_OURS,
            enabled = unresolved > 0,
            onClick = { state.resolveRemaining(MergeChoice.Ours) },
        )
        MergeAction(
            "All theirs",
            EditorTags.MERGE_ALL_THEIRS,
            enabled = unresolved > 0,
            onClick = { state.resolveRemaining(MergeChoice.Theirs) },
        )
        Spacer(Modifier.weight(1f))
        MergeAction(
            "History",
            EditorTags.MERGE_HISTORY_TOGGLE,
            enabled = true,
            selected = state.historyVisible,
            onClick = { state.historyVisible = !state.historyVisible },
        )
        MergeAction("Cancel", EditorTags.MERGE_CANCEL, enabled = true, onClick = onCancel)
        MergeAction("Apply", EditorTags.MERGE_APPLY, enabled = unresolved == 0, primary = true, onClick = onApply)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

// A plain text action: no border, a pill only for the selected one and for the primary action.
@Composable
private fun MergeAction(
    text: String,
    tag: String,
    enabled: Boolean,
    primary: Boolean = false,
    selected: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    val filled = primary && enabled
    val background =
        when {
            filled -> IdeColors.Accent
            selected -> IdeColors.Selection
            else -> Color.Transparent
        }
    val content =
        when {
            filled -> Color.White
            !enabled -> IdeColors.LineNumber
            selected -> IdeColors.Text
            else -> IdeColors.TextSecondary
        }
    Box(
        Modifier
            .padding(start = 2.dp)
            .testTag(tag)
            .background(background, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Label(text, color = content, fontSize = 12.sp)
    }
}

@Composable
private fun ColumnTitles(state: MergeState) {
    Row(
        Modifier.fillMaxWidth().height(24.dp).background(IdeColors.Panel),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ColumnTitle(state.oursLabel, Modifier.weight(1f))
        ColumnTitle("Result", Modifier.weight(1f))
        ColumnTitle(state.theirsLabel, Modifier.weight(1f))
    }
}

@Composable
private fun ColumnTitle(text: String, modifier: Modifier) {
    Label(text, modifier.padding(horizontal = 8.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
}

// The title of a stretch that changed, with the choices that decide what the result holds for it.
@Composable
private fun MergeHeading(state: MergeState, chunk: Int) {
    val kind = state.chunks[chunk].kind
    val chosen = state.choiceOf(chunk)
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp)
            .testTag(EditorTags.mergeHeading(chunk))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val pending = chosen == MergeChoice.Pending
        val titleColor = if (pending) IdeColors.GitBehind else IdeColors.LineNumber
        Label(headingOf(kind, chosen), color = titleColor, fontSize = 11.sp)
        Spacer(Modifier.weight(1f))
        for (choice in choicesOf(kind)) {
            MergeAction(
                choice.name,
                EditorTags.mergeChoice(chunk, choice.name),
                enabled = true,
                selected = chosen == choice,
                onClick = { state.choose(chunk, choice) },
            )
        }
    }
}

private fun headingOf(kind: MergeKind, chosen: MergeChoice): String {
    val what =
        when (kind) {
            MergeKind.Conflict -> if (chosen == MergeChoice.Pending) "Conflict" else "Conflict resolved"
            MergeKind.OursOnly -> "Changed in ours"
            MergeKind.TheirsOnly -> "Changed in theirs"
            MergeKind.BothSame -> "Changed the same way on both sides"
            MergeKind.Unchanged -> ""
        }
    return what
}

@Composable
private fun MergeLine(state: MergeState, index: Int, row: MergeRow, style: TextStyle) {
    val chunk = state.chunks[row.chunk]
    val pending = state.choiceOf(row.chunk) == MergeChoice.Pending
    val changed = chunk.kind != MergeKind.Unchanged
    val conflict = chunk.kind == MergeKind.Conflict
    val oursOwn = changed && chunk.kind != MergeKind.TheirsOnly
    val theirsOwn = changed && chunk.kind != MergeKind.OursOnly
    Row(Modifier.fillMaxWidth().height(DIFF_LINE_HEIGHT).testTag(EditorTags.mergeRow(index))) {
        val ours = sideBackground(oursOwn, conflict)
        val theirs = sideBackground(theirsOwn, conflict)
        val result = resultBackground(changed, pending)
        MergeCell(row.ours, row.oursNumber, ours, EditorTags.mergeCell(index, COLUMN_OURS), style, Modifier.weight(1f))
        Box(Modifier.fillMaxHeight().width(1.dp).background(IdeColors.Border))
        MergeCell(
            row.result,
            row.resultNumber,
            result,
            EditorTags.mergeCell(index, COLUMN_RESULT),
            style,
            Modifier.weight(1f),
        )
        Box(Modifier.fillMaxHeight().width(1.dp).background(IdeColors.Border))
        MergeCell(
            row.theirs,
            row.theirsNumber,
            theirs,
            EditorTags.mergeCell(index, COLUMN_THEIRS),
            style,
            Modifier.weight(1f),
        )
    }
}

// A side that changed the stretch shows it as an addition, or as a conflict when the other side changed it too.
private fun sideBackground(changedHere: Boolean, conflict: Boolean): Color =
    when {
        !changedHere -> Color.Transparent
        conflict -> IdeColors.MergeConflictBackground
        else -> IdeColors.DiffAddedBackground
    }

private fun resultBackground(changed: Boolean, pending: Boolean): Color =
    when {
        pending -> IdeColors.MergeConflictBackground
        changed -> IdeColors.MergeResolvedBackground
        else -> Color.Transparent
    }

@Composable
private fun MergeCell(
    text: String?,
    number: Int,
    background: Color,
    tag: String,
    style: TextStyle,
    modifier: Modifier,
) {
    Row(
        modifier
            .fillMaxHeight()
            .clipToBounds()
            .background(
                if (text == null) Color.Transparent else background,
            ).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineNumber(number)
        BasicText(
            text.orEmpty(),
            Modifier.padding(start = 8.dp),
            style = style.copy(color = IdeColors.Code),
            softWrap = false,
            maxLines = 1,
        )
    }
}

private val HISTORY_HEIGHT = 180.dp
private val HISTORY_ROW = 22.dp

// The commits that changed the file on each side since the two sides diverged, loaded when the panel opens.
@Composable
private fun MergeHistoryPanel(loadHistory: suspend () -> MergeHistory, onOpenCommit: (GitCommit) -> Unit) {
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
