package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.ProjectState

/**
 * The content of the agent window, beside the IDE window of the same project: the conversations of the agent, in
 * their own window. Closing the window hides it; the conversations stay open.
 */
@Composable
fun AgentWindowContent(app: AppState, project: ProjectState) {
    val findFocus = remember { FocusRequester() }
    val agentShortcuts = remember(project) { AgentShortcuts(IdeContext(app, project, findFocus)) }
    // Like the IDE window, the project is saved when this window loses the focus and synchronized when it gets it back.
    SyncOnWindowFocus(project)
    IdeTheme(app.settings.theme) {
        Box(
            Modifier
                .fillMaxSize()
                .background(IdeColors.Frame)
                .onPreviewKeyEvent(agentShortcuts::handle)
                .rootKeyboardFocus(app, project),
        ) {
            AgentWorkspace(app, project)
        }
        // The settings of the agent window, its own: opening them here leaves the dialog of the IDE window closed.
        val dialogs = app.settings.dialogs
        SettingsDialog(app, dialogs.agent) { dialogs.closeAgent() }
    }
}

/** The title of the agent window: the project, then the conversation shown. */
fun agentWindowTitle(project: ProjectState): String {
    val conversation = project.agent.current?.title
    return listOfNotNull(project.name, "Agent", conversation).joinToString(" – ")
}
