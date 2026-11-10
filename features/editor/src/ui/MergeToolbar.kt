package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.MergeChoice
import org.example.kotlineditor.state.MergeState

@Composable
internal fun MergeToolbar(state: MergeState, onNext: () -> Unit, onApply: () -> Unit, onCancel: () -> Unit) {
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
internal fun MergeAction(
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
