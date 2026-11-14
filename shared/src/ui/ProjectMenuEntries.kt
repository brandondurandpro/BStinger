package org.example.kotlineditor.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppState

// The same dialog as the welcome screen's: connect to another machine over SSH, from the open project.
@Composable
internal fun RemoteMenuItem(app: AppState, close: () -> Unit) {
    IdeMenuItem(
        text = { Text("Remote Development…", fontSize = 13.sp) },
        onClick = {
            close()
            app.remote.openDialog()
        },
        modifier = Modifier.testTag(AppTags.PROJECT_REMOTE),
    )
}

@Composable
internal fun SettingsMenuItem(app: AppState, close: () -> Unit) {
    IdeMenuItem(
        text = { Text("Settings…", fontSize = 13.sp) },
        trailingIcon = { Text("Ctrl+Alt+S", fontSize = 12.sp, color = IdeColors.TextSecondary) },
        onClick = {
            close()
            app.settings.open()
        },
        modifier = Modifier.testTag(AppTags.PROJECT_SETTINGS),
    )
}

@Composable
internal fun HomepageMenuItem(app: AppState, close: () -> Unit) {
    IdeMenuItem(
        text = { Text("Go Homepage", fontSize = 13.sp) },
        onClick = {
            close()
            app.session.closeProject()
        },
        modifier = Modifier.testTag(AppTags.PROJECT_CLOSE),
    )
}
