package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppUpdater
import org.example.kotlineditor.state.AvailableRelease
import org.example.kotlineditor.state.UpdateNotifier
import org.example.kotlineditor.state.UpdatePlan
import org.example.kotlineditor.state.UpdateState

private const val BYTES_PER_MEGABYTE = 1024L * 1024L
private const val PERCENT = 100L
private const val LATER = "Later"

// The content of the update dialog for each step of the update; the frame, the focus and the keys are in UpdateDialog.

@Composable
internal fun Message(text: String, tag: String = AppTags.UPDATE_MESSAGE) {
    Label(text, color = IdeColors.TextSecondary, modifier = Modifier.padding(top = 4.dp).testTag(tag))
}

@Composable
internal fun Buttons(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
    ) {
        content()
    }
}

@Composable
internal fun OfferBody(updates: UpdateNotifier, release: AvailableRelease, close: () -> Unit) {
    val running = updates.running?.toString() ?: "an unknown version"
    Message("Version ${release.version} is available. You are running $running.")
    // Looked up once per release: it reads the disk (where the IDE is installed, what can be written).
    val plan = remember(release) { updates.updater.planFor(release) }
    when (plan) {
        is UpdatePlan.InApp -> {
            Message(
                "The IDE restarts to install it: its windows and unsaved files are saved first.",
                AppTags.UPDATE_NOTE,
            )
            Buttons {
                IdeButton(LATER, close, Modifier.testTag(AppTags.UPDATE_LATER))
                IdeButton("Release notes", { updates.openReleasePage(release) }, Modifier.testTag(AppTags.UPDATE_NOTES))
                IdeButton(
                    "Update",
                    { updates.updater.start(release) },
                    Modifier.testTag(AppTags.UPDATE_INSTALL),
                    primary = true,
                )
            }
        }

        is UpdatePlan.Manual -> {
            Message(plan.reason, AppTags.UPDATE_REASON)
            Buttons {
                IdeButton(LATER, close, Modifier.testTag(AppTags.UPDATE_LATER))
                IdeButton(
                    "Open download page",
                    {
                        updates.openReleasePage(release)
                        close()
                    },
                    Modifier.testTag(AppTags.UPDATE_DOWNLOAD_PAGE),
                    primary = true,
                )
            }
        }
    }
}

@Composable
internal fun DownloadingBody(state: UpdateState.Downloading, updater: AppUpdater) {
    Message("Downloading version ${state.release.version}…")
    ProgressBar(fractionOf(state), Modifier.fillMaxWidth().padding(top = 8.dp))
    Message(progressText(state), AppTags.UPDATE_PROGRESS)
    Buttons { IdeButton("Cancel", updater::cancel, Modifier.testTag(AppTags.UPDATE_CANCEL)) }
}

@Composable
internal fun ReadyBody(release: AvailableRelease, updater: AppUpdater, close: () -> Unit) {
    Message("Version ${release.version} is ready. Restart the IDE to install it.")
    Buttons {
        IdeButton(LATER, close, Modifier.testTag(AppTags.UPDATE_LATER))
        IdeButton("Restart", updater::requestRestart, Modifier.testTag(AppTags.UPDATE_RESTART), primary = true)
    }
}

@Composable
internal fun FailedBody(state: UpdateState.Failed, updater: AppUpdater, close: () -> Unit) {
    Label(
        "The update failed: ${state.message}",
        color = IdeColors.Error,
        fontSize = 12.sp,
        modifier = Modifier.padding(top = 6.dp).testTag(AppTags.UPDATE_ERROR),
    )
    Buttons {
        IdeButton("Close", { closeAfterFailure(updater, close) }, Modifier.testTag(AppTags.UPDATE_CLOSE))
        IdeButton("Retry", updater::retry, Modifier.testTag(AppTags.UPDATE_RETRY), primary = true)
    }
}

internal fun fractionOf(state: UpdateState.Downloading): Float =
    if (state.total > 0) state.done.toFloat() / state.total else 0f

internal fun progressText(state: UpdateState.Downloading): String {
    val done = state.done / BYTES_PER_MEGABYTE
    if (state.total <= 0) return "$done MB"
    val total = state.total / BYTES_PER_MEGABYTE
    val percent = state.done * PERCENT / state.total
    return "$done / $total MB ($percent%)"
}
