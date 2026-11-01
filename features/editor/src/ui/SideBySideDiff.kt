package org.example.kotlineditor.ui

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine

/** Side-by-side diff: the old file on the left, the new file on the right, one line of the diff per row. */
@Composable
internal fun SideBySideDiff(listState: LazyListState, lines: List<DiffLine>, style: TextStyle) {
    val rows = sideBySideRows(lines)
    Box(Modifier.fillMaxSize().testTag(EditorTags.DIFF_SPLIT)) {
        LazyColumn(Modifier.fillMaxSize(), state = listState) {
            itemsIndexed(rows, key = { index, _ -> index }) { index, row ->
                if (row.hunkText == null) {
                    SideBySideRow(index, row, style)
                } else {
                    HunkRow(index, row.hunkText, style)
                }
            }
        }
        VerticalScrollbar(
            rememberScrollbarAdapter(listState),
            Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

/** One line of the side-by-side layout: the old file on the left, the new file on the right. */
internal data class SideBySideDiffRow(val left: DiffCell?, val right: DiffCell?, val hunkText: String? = null)

/** One cell of a [SideBySideDiffRow] (a blank cell is null). */
internal data class DiffCell(val number: Int, val text: String, val kind: DiffKind)

/**
 * Pairs the unified diff lines for a side-by-side layout: within each changed block, removed lines face added
 * lines, and the shorter side gets blank cells. Hunk headers and context lines span both sides.
 */
internal fun sideBySideRows(lines: List<DiffLine>): List<SideBySideDiffRow> {
    val rows = ArrayList<SideBySideDiffRow>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        when (line.kind) {
            DiffKind.Hunk -> {
                rows += SideBySideDiffRow(left = null, right = null, hunkText = line.text)
                index++
            }

            DiffKind.Context -> {
                rows +=
                    SideBySideDiffRow(
                        left = DiffCell(line.oldLine, line.text, line.kind),
                        right = DiffCell(line.newLine, line.text, line.kind),
                    )
                index++
            }

            DiffKind.Removed, DiffKind.Added -> {
                val removed = ArrayList<DiffCell>()
                val added = ArrayList<DiffCell>()
                while (index < lines.size && lines[index].kind.isChange()) {
                    val changed = lines[index]
                    if (changed.kind == DiffKind.Removed) {
                        removed += DiffCell(changed.oldLine, changed.text, changed.kind)
                    } else {
                        added += DiffCell(changed.newLine, changed.text, changed.kind)
                    }
                    index++
                }
                repeat(maxOf(removed.size, added.size)) { pair ->
                    rows += SideBySideDiffRow(left = removed.getOrNull(pair), right = added.getOrNull(pair))
                }
            }
        }
    }
    return rows
}

private fun DiffKind.isChange(): Boolean = this == DiffKind.Removed || this == DiffKind.Added

@Composable
private fun HunkRow(index: Int, text: String, style: TextStyle) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(DIFF_LINE_HEIGHT)
            .background(IdeColors.DiffHunkBackground)
            .testTag(EditorTags.diffSplitRow(index))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            text,
            style = style.copy(color = IdeColors.TextSecondary),
            softWrap = false,
            maxLines = 1,
        )
    }
}

@Composable
private fun SideBySideRow(index: Int, row: SideBySideDiffRow, style: TextStyle) {
    Row(Modifier.fillMaxWidth().height(DIFF_LINE_HEIGHT).testTag(EditorTags.diffSplitRow(index))) {
        SplitCell(row.left, index, SIDE_LEFT, style, Modifier.weight(1f))
        Box(Modifier.fillMaxHeight().width(1.dp).background(IdeColors.Border))
        SplitCell(row.right, index, SIDE_RIGHT, style, Modifier.weight(1f))
    }
}

private const val SIDE_LEFT = 0
private const val SIDE_RIGHT = 1

@Composable
private fun SplitCell(cell: DiffCell?, index: Int, side: Int, style: TextStyle, modifier: Modifier) {
    Row(
        modifier
            .fillMaxHeight()
            .clipToBounds()
            .background(cell?.let { backgroundOf(it.kind) } ?: Color.Transparent)
            .testTag(EditorTags.diffSplitCell(index, side)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LineNumber(cell?.number ?: -1)
        BasicText(
            cell?.let { markerOf(it.kind) } ?: "",
            Modifier.width(18.dp),
            style = style.copy(color = cell?.let { textColorOf(it.kind) } ?: Color.Transparent),
            maxLines = 1,
        )
        BasicText(
            cell?.text ?: "",
            style = style.copy(color = cell?.let { textColorOf(it.kind) } ?: Color.Transparent),
            softWrap = false,
            maxLines = 1,
        )
    }
}
