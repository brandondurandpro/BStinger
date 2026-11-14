package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.ReviewFile
import org.example.kotlineditor.state.ReviewState
import org.example.kotlineditor.syntax.treesitter.LineSpans
import java.io.IOException

private val FILE_LIST_WIDTH = 300.dp
private val ROW_HEIGHT = 26.dp

// What the right side shows of the selected file: its changes, or why they cannot be shown.
private sealed interface ReviewDiff {
    data class Loaded(val lines: List<DiffLine>) : ReviewDiff

    data class Failed(val message: String) : ReviewDiff
}

/**
 * A review tab: the files to review on the left, each with a checkbox (checked is OK), and on the right the diff of
 * the file selected on the left.
 */
@Composable
fun ReviewView(
    state: ReviewState,
    loadDiff: suspend (FileEntry) -> List<DiffLine>,
    spansOf: suspend (String, List<DiffLine>) -> List<LineSpans?>?,
    sideBySide: Boolean,
    onToggleSideBySide: () -> Unit,
    onOpenFile: (FileEntry, List<DiffLine>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxSize().background(IdeColors.Editor).testTag(EditorTags.REVIEW)) {
        ReviewFileList(state, Modifier.width(FILE_LIST_WIDTH).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(IdeColors.Separator))
        Box(Modifier.weight(1f).fillMaxHeight().testTag(EditorTags.REVIEW_DIFF_AREA)) {
            val file = state.selectedFile
            if (file == null) {
                ReviewMessage("No file to review", EditorTags.REVIEW_EMPTY)
            } else {
                ReviewDiffArea(file, loadDiff, spansOf, sideBySide, onToggleSideBySide, onOpenFile)
            }
        }
    }
}

@Composable
private fun ReviewFileList(state: ReviewState, modifier: Modifier) {
    Column(modifier.background(IdeColors.Panel).testTag(EditorTags.REVIEW_LIST)) {
        val total = state.files.size
        Label(
            "OK: ${state.approvedCount} / $total",
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(EditorTags.REVIEW_PROGRESS),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        LazyColumn(Modifier.fillMaxSize()) {
            items(state.files, key = { it.relativePath }) { file -> ReviewRow(state, file) }
        }
    }
}

// One file: the checkbox says OK (checked) or not OK, a click on the name shows its diff.
@Composable
private fun ReviewRow(state: ReviewState, file: ReviewFile) {
    val path = file.relativePath
    val selected = state.selected == path
    val directory = path.substringBeforeLast('/', "")
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) IdeColors.Selection else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReviewCheckbox(
            state.isApproved(path),
            Modifier.padding(start = 10.dp).testTag(EditorTags.reviewCheck(path)),
        ) { state.toggle(path) }
        Row(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .clickable { state.select(path) }
                .testTag(EditorTags.reviewFile(path))
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FileIcon(file.entry.name, isDirectory = false)
            Label(file.entry.name, Modifier.padding(start = 6.dp))
            if (directory.isNotEmpty()) {
                Label(
                    directory,
                    Modifier.weight(1f).padding(start = 6.dp),
                    color = IdeColors.TextSecondary,
                    fontSize = 11.sp,
                )
            }
        }
    }
}

@Composable
private fun ReviewCheckbox(checked: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = IdeShapes.Small
    val borderColor = if (checked) IdeColors.Accent else IdeColors.InputBorder
    Box(
        modifier
            .size(14.dp)
            .background(if (checked) IdeColors.Accent else Color.Transparent, shape)
            .border(1.dp, borderColor, shape)
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) CheckIcon(Modifier.size(9.dp), color = IdeColors.OnAccent)
    }
}

@Composable
private fun ReviewDiffArea(
    file: ReviewFile,
    loadDiff: suspend (FileEntry) -> List<DiffLine>,
    spansOf: suspend (String, List<DiffLine>) -> List<LineSpans?>?,
    sideBySide: Boolean,
    onToggleSideBySide: () -> Unit,
    onOpenFile: (FileEntry, List<DiffLine>) -> Unit,
) {
    val loaded by produceState<ReviewDiff?>(null, file.relativePath) {
        value = null
        value =
            try {
                ReviewDiff.Loaded(loadDiff(file.entry))
            } catch (e: IOException) {
                ReviewDiff.Failed("Cannot compute the differences: ${e.message}")
            }
    }
    when (val diff = loaded) {
        null -> {
            Box(Modifier.fillMaxSize().testTag(EditorTags.REVIEW_LOADING))
        }

        is ReviewDiff.Failed -> {
            ReviewMessage(diff.message, EditorTags.REVIEW_ERROR)
        }

        is ReviewDiff.Loaded -> {
            val spans by produceState<List<LineSpans?>?>(null, diff.lines) {
                value = spansOf(file.entry.name, diff.lines)
            }
            // The scroll positions belong to the file shown, a tab of the diff of that file only holds them.
            val diffTab = remember(file.relativePath) { EditorTab(file.entry, file.relativePath, isDiff = true) }
            DiffView(
                diffTab,
                diff.lines,
                sideBySide = sideBySide,
                onToggleSideBySide = onToggleSideBySide,
                onOpenFile = { onOpenFile(file.entry, diff.lines) },
                spans = spans,
            )
        }
    }
}

@Composable
private fun ReviewMessage(text: String, tag: String) {
    Box(Modifier.fillMaxSize().padding(12.dp).testTag(tag)) {
        Label(text, color = IdeColors.TextSecondary, fontSize = 12.sp)
    }
}
