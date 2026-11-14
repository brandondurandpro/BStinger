package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.AppState

// The IDE frame of the homepage (no project open): the toolbar with the menu and the status bar of a project, around
// the recent projects, Remote Development and Settings.

private val HOME_MENU_MIN_WIDTH = 320.dp
private val HOME_MENU_MAX_WIDTH = 900.dp
private val HOME_STATUS_HEIGHT = 26.dp

@Composable
internal fun HomeToolbar(app: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(IdeColors.Frame)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HomeWidget(app)
        if (app.session.isRemote) RemoteBadge(app)
    }
}

@Composable
private fun HomeWidget(app: AppState) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(28.dp)
                .border(1.dp, IdeColors.Border, IdeShapes.Medium)
                .clip(IdeShapes.Medium)
                .clickable { menuOpen = true }
                .testTag(AppTags.HOME_WIDGET)
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProjectBadge("Kotlin Editor")
            Label("Home", Modifier.padding(start = 8.dp), fontWeight = FontWeight.SemiBold)
            Chevron(expanded = true, Modifier.padding(start = 2.dp))
        }
        IdeDropdownMenu(
            menuOpen,
            onDismissRequest = { menuOpen = false },
            Modifier
                .widthIn(min = HOME_MENU_MIN_WIDTH, max = HOME_MENU_MAX_WIDTH)
                .testTag(AppTags.HOME_MENU)
                .onPreviewKeyEvent { event ->
                    val escape = event.key == Key.Escape && event.type == KeyEventType.KeyDown
                    if (escape) menuOpen = false
                    escape
                },
        ) {
            ProjectMenuItems(app, home = true) { menuOpen = false }
        }
    }
}

@Composable
internal fun HomeStatusBar(app: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(HOME_STATUS_HEIGHT)
            .background(IdeColors.Frame)
            .padding(horizontal = 12.dp)
            .testTag(AppTags.HOME_STATUS_BAR),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Label("Home", Modifier.weight(1f), color = IdeColors.TextSecondary, fontSize = 12.sp)
        app.session.remoteLabel?.let {
            Label("Remote: $it", color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        LinkStatus(app)
    }
}
