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

/**
 * An icon in the status bar when a release newer than the IDE is published; a click opens its page on GitHub.
 * Nothing is shown otherwise, and never an error: a check that fails is silent.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun UpdateIndicator(app: AppState) {
    val updates = app.updates ?: return
    val tag = updates.available ?: return
    val description = "Version ${tag.removePrefix("v")} is available: click to see what is new"
    TooltipArea(tooltip = { Tooltip(description) }) {
        Row(
            Modifier.clickable(onClick = updates::openReleasePage).testTag(AppTags.UPDATE_AVAILABLE),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArrowIcon(up = true, color = IdeColors.Accent)
        }
    }
}
