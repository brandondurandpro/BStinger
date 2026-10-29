package org.example.kotlineditor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.ui.IdeTheme
import org.example.kotlineditor.ui.ProjectScreen
import org.example.kotlineditor.ui.WelcomeScreen

@Composable
fun App(state: AppState) {
    IdeTheme {
        when (val screen = state.screen) {
            Screen.Welcome -> WelcomeScreen(state)
            is Screen.Project -> key(screen.project) { ProjectScreen(state, screen.project) }
        }
    }
}
