package org.example.kotlineditor.ui

import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.ProjectState

// Shortcuts only reach a focused component: with no file open nothing else holds the focus, so the window's root takes
// it. With the Open Project or the Settings dialog open, its own field keeps the keyboard. When the agent window
// closes, its keyboard comes back to this window: to the file shown, else to the root.
@Composable
internal fun Modifier.rootKeyboardFocus(app: AppState, project: ProjectState): Modifier {
    val rootFocus = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    val screenFocusFree = project.popup == null && !project.git.branchPickerOpen && !app.dialogOpen
    LaunchedEffect(hasFocus, screenFocusFree, windowFocused) {
        if (!hasFocus && screenFocusFree) runCatching { rootFocus.requestFocus() }
    }
    LaunchedEffect(project.agentMode) {
        if (!project.agentMode) takeKeyboardFocus(project, rootFocus)
    }
    return this.focusRequester(rootFocus).onFocusChanged { hasFocus = it.hasFocus }.focusable()
}

// The keyboard goes to the file the window shows, else to its root, which holds the shortcuts.
private fun takeKeyboardFocus(project: ProjectState, rootFocus: FocusRequester) {
    val editor = project.editor
    val showsFile = editor.activeTab?.document != null
    val target = if (showsFile) editor.activePane.focusRequester else rootFocus
    runCatching { target.requestFocus() }
}
