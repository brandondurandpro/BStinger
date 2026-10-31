package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.state.EditorTab

internal val DIFF_LINE_HEIGHT = 20.dp
private val LINE_NUMBER_WIDTH = 44.dp

/** Diff tab: the changes of a file since HEAD, unified or side by side, with green additions and red removals. */
@Composable
internal fun DiffView(
    tab: EditorTab,
    lines: List<DiffLine>,
    sideBySide: Boolean,
    onToggleSideBySide: () -> Unit,
    onOpenFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = LocalCodeStyle.current
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(Tags.DIFF)) {
        DiffToolbar(sideBySide, onToggleSideBySide, onOpenFile)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (sideBySide) {
                SideBySideDiff(tab.splitListState, lines, style)
            } else {
                UnifiedDiff(tab, lines, style)
            }
        }
    }
}

@Composable
private fun DiffToolbar(sideBySide: Boolean, onToggleSideBySide: () -> Unit, onOpenFile: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .background(IdeColors.Panel)
            .testTag(Tags.DIFF_TOOLBAR)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OpenFileButton(onOpenFile)
        Spacer(Modifier.weight(1f))
        DiffLayoutToggle(sideBySide, onToggleSideBySide)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

// Opens the file itself, at its first changed line (F4, like IntelliJ's Jump to Source).
@Composable
private fun OpenFileButton(onOpenFile: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .testTag(Tags.DIFF_OPEN_FILE)
            .border(1.dp, IdeColors.InputBorder, shape)
            .clickable(onClick = onOpenFile)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlainFileIcon(Modifier.size(14.dp))
        Label("Open File", Modifier.padding(start = 4.dp), color = IdeColors.TextSecondary, fontSize = 11.sp)
        Label("F4", Modifier.padding(start = 6.dp), color = IdeColors.LineNumber, fontSize = 10.sp)
    }
}

@Composable
private fun DiffLayoutToggle(sideBySide: Boolean, onToggle: () -> Unit) {
    val color = if (sideBySide) Color.White else IdeColors.TextSecondary
    val background = if (sideBySide) IdeColors.Accent else Color.Transparent
    val border = if (sideBySide) IdeColors.Accent else IdeColors.InputBorder
    val shape = RoundedCornerShape(4.dp)
    Row(
        Modifier
            .testTag(Tags.DIFF_LAYOUT_TOGGLE)
            .background(background, shape)
            .border(1.dp, border, shape)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SplitDiffIcon(color = color)
        Label("Side by side", Modifier.padding(start = 4.dp), color = color, fontSize = 11.sp)
    }
}

@Composable
private fun UnifiedDiff(tab: EditorTab, lines: List<DiffLine>, style: TextStyle) {
    Box(Modifier.fillMaxSize()) {
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
internal fun LineNumber(number: Int) {
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

internal fun backgroundOf(kind: DiffKind): Color =
    when (kind) {
        DiffKind.Added -> IdeColors.DiffAddedBackground
        DiffKind.Removed -> IdeColors.DiffRemovedBackground
        DiffKind.Hunk -> IdeColors.DiffHunkBackground
        DiffKind.Context -> Color.Transparent
    }

internal fun textColorOf(kind: DiffKind): Color =
    when (kind) {
        DiffKind.Added -> IdeColors.GitAdded
        DiffKind.Removed -> IdeColors.GitUntracked
        DiffKind.Hunk -> IdeColors.TextSecondary
        DiffKind.Context -> IdeColors.Code
    }

internal fun markerOf(kind: DiffKind): String =
    when (kind) {
        DiffKind.Added -> "+"
        DiffKind.Removed -> "-"
        DiffKind.Hunk -> ""
        DiffKind.Context -> " "
    }
