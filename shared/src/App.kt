package org.example.kotlineditor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.ui.IdeTheme
import org.example.kotlineditor.ui.ProjectScreen
import org.example.kotlineditor.ui.RemoteDialog
import org.example.kotlineditor.ui.WelcomeScreen

@Composable
fun App(state: AppState) {
    IdeTheme {
        Box(Modifier.fillMaxSize()) {
            when (val screen = state.screen) {
                Screen.Welcome -> WelcomeScreen(state)
                is Screen.Project -> key(screen.project) { ProjectScreen(state, screen.project) }
            }
            state.remoteDialog?.let { RemoteDialog(state, it) }
        }
    }
}
