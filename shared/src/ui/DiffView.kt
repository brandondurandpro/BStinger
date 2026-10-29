package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.state.EditorTab

private val DIFF_LINE_HEIGHT = 20.dp
private val LINE_NUMBER_WIDTH = 44.dp

/** Diff tab: the changes of a file since HEAD, with old and new line numbers, green additions and red removals. */
@Composable
internal fun DiffView(tab: EditorTab, lines: List<DiffLine>, modifier: Modifier = Modifier) {
    val style = LocalCodeStyle.current
    Box(modifier.fillMaxSize().background(IdeColors.Editor).testTag(Tags.DIFF)) {
        LazyColumn(Modifier.fillMaxSize(), state = tab.listState) {
            itemsIndexed(lines, key = { index, _ -> index }) { index, line ->
                DiffRow(index, line, style)
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(tab.listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun DiffRow(index: Int, line: DiffLine, style: TextStyle) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DIFF_LINE_HEIGHT)
            .background(backgroundOf(line.kind))
            .testTag(Tags.diffLine(index)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineNumber(line.oldLine)
        LineNumber(line.newLine)
        BasicText(
            markerOf(line.kind),
            Modifier.width(18.dp),
            style = style.copy(color = textColorOf(line.kind)),
            maxLines = 1,
        )
        BasicText(
            line.text,
            Modifier.testTag(Tags.diffLineKind(index, line.kind.name)),
            style = style.copy(color = textColorOf(line.kind)),
            softWrap = false,
            maxLines = 1,
        )
    }
}

@Composable
private fun LineNumber(number: Int) {
    Box(Modifier.width(LINE_NUMBER_WIDTH)) {
        BasicText(
            if (number < 0) "" else number.toString(),
            Modifier.fillMaxWidth(),
            style =
            TextStyle(
                color = IdeColors.LineNumber,
                fontSize = LocalCodeStyle.current.fontSize,
                textAlign = TextAlign.End,
            ),
            maxLines = 1,
        )
    }
}

private fun backgroundOf(kind: DiffKind): Color =
    when (kind) {
        DiffKind.Added -> IdeColors.DiffAddedBackground
        DiffKind.Removed -> IdeColors.DiffRemovedBackground
        DiffKind.Hunk -> IdeColors.DiffHunkBackground
        DiffKind.Context -> Color.Transparent
    }

private fun textColorOf(kind: DiffKind): Color =
    when (kind) {
        DiffKind.Added -> IdeColors.GitAdded
        DiffKind.Removed -> IdeColors.GitUntracked
        DiffKind.Hunk -> IdeColors.TextSecondary
        DiffKind.Context -> IdeColors.Code
    }

private fun markerOf(kind: DiffKind): String =
    when (kind) {
        DiffKind.Added -> "+"
        DiffKind.Removed -> "-"
        DiffKind.Hunk -> ""
        DiffKind.Context -> " "
    }
