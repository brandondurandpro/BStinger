package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.UpdateState

/**
 * An icon in the status bar when a release newer than the IDE is published (or an update is under way); a click opens
 * the update dialog. Nothing is shown otherwise, and never an error: a check that fails is silent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UpdateIndicator(app: AppState) {
    val updates = app.updates ?: return
    val release = updates.updater.state.let { (it as? UpdateState.Pinned)?.release } ?: updates.available ?: return
    val description = "Version ${release.version} is available: click to update"
    TooltipArea(tooltip = { Tooltip(description) }) {
        Row(
            Modifier.clickable { app.updateDialogOpen = true }.testTag(AppTags.UPDATE_AVAILABLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrowIcon(up = true, color = IdeColors.Accent)
        }
    }
}
