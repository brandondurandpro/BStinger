package org.example.kotlineditor.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.AppUpdater
import org.example.kotlineditor.state.AvailableRelease
import org.example.kotlineditor.state.UpdateNotifier
import org.example.kotlineditor.state.UpdateState

/** Closes this window when an update asks the IDE to restart; the windows close like the user would close them. */
@Composable
fun CloseForUpdateRestart(state: AppState) {
    val restarting = state.updates?.updater?.restarting ?: false
    LaunchedEffect(restarting) { if (restarting) state.exit(state.onClosed) }
}

/**
 * The update of the IDE, opened by the icon of the status bar: asks whether to update, shows the download, the
 * failure (never silent here, the user asked) and the restart. Everything shown is the state shared by the windows
 * ([AppUpdater]); an install that cannot update itself says why and sends to the release page. Escape and a click
 * outside are the gentlest choice of the moment: Later, or Cancel while downloading.
 */
@Composable
fun UpdateDialog(app: AppState) {
    val updates = app.updates ?: return
    val state = updates.updater.state
    val release = (state as? UpdateState.Pinned)?.release ?: updates.available
    if (release != null && app.updateDialogOpen) UpdateDialogFrame(app, updates, state, release)
}

@Composable
private fun UpdateDialogFrame(app: AppState, updates: UpdateNotifier, state: UpdateState, release: AvailableRelease) {
    val updater = updates.updater
    val close = { app.updateDialogOpen = false }
    val dismiss = { dismiss(updater, state, close) }
    val focus = remember { FocusRequester() }
    // Each step replaces the buttons and the focus of the one clicked goes with them: Escape needs it back.
    LaunchedEffect(state::class) { runCatching { focus.requestFocus() } }
    PopupFrame(AppTags.UPDATE_DIALOG, dismiss, width = 520) {
        Column(Modifier.focusRequester(focus).focusable().popupKeys({}, dismiss)) {
            Label("Update Kotlin Editor", fontWeight = FontWeight.SemiBold)
            when (state) {
                is UpdateState.Downloading -> DownloadingBody(state, updater)
                is UpdateState.Staging -> Message("Preparing version ${release.version}…")
                is UpdateState.Ready -> ReadyBody(state, updater, close)
                is UpdateState.Restarting -> Message("Closing the IDE to install version ${release.version}…")
                is UpdateState.Failed -> FailedBody(state, updater, close)
                UpdateState.Idle -> OfferBody(updates, release, close)
            }
        }
    }
}

private fun dismiss(updater: AppUpdater, state: UpdateState, close: () -> Unit) {
    when (state) {
        is UpdateState.Downloading -> updater.cancel()
        is UpdateState.Failed -> closeAfterFailure(updater, close)
        else -> close()
    }
}

internal fun closeAfterFailure(updater: AppUpdater, close: () -> Unit) {
    updater.dismissFailure()
    close()
}
