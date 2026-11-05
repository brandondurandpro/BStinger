package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.ExitSaveFailure

/**
 * Closing the IDE while files could not be saved: lists them with why, and lets the user try again, close anyway
 * (their edits are lost) or keep the IDE open.
 */
@Composable
fun UnsavedOnExitDialog(state: AppState, failure: ExitSaveFailure) {
    PopupFrame(AppTags.UNSAVED_DIALOG, state.exiting::cancel, width = 520) {
        Column {
            Label("Some files could not be saved", fontWeight = FontWeight.SemiBold)
            Label(
                "Their edits are lost if the IDE closes now.",
                color = IdeColors.TextSecondary,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            for (file in failure.files) {
                Column(Modifier.padding(vertical = 3.dp).testTag(AppTags.unsavedFile(file.relativePath))) {
                    Label(file.relativePath)
                    Label(file.reason, color = IdeColors.Error, fontSize = 12.sp)
                }
            }
            if (failure.saving) {
                Label(
                    "Saving…",
                    color = IdeColors.TextSecondary,
                    modifier = Modifier.padding(top = 6.dp).testTag(AppTags.UNSAVED_SAVING),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                IdeButton("Cancel", state.exiting::cancel, Modifier.testTag(AppTags.UNSAVED_CANCEL))
                IdeButton(
                    "Close Anyway",
                    state.exiting::closeAnyway,
                    Modifier.padding(start = 8.dp).testTag(AppTags.UNSAVED_CLOSE_ANYWAY),
                )
                IdeButton(
                    "Retry",
                    state.exiting::retry,
                    Modifier.padding(start = 8.dp).testTag(AppTags.UNSAVED_RETRY),
                    primary = true,
                )
            }
        }
    }
}
