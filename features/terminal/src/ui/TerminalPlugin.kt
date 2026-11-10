package org.example.kotlineditor.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.text.font.FontWeight
import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ServiceKey
import org.example.kotlineditor.state.TerminalState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults

/** The terminal state of a project, as [org.example.kotlineditor.state.ProjectHost.service] gives it. */
val TERMINAL_SERVICE = ServiceKey<TerminalState>("terminal")

/** The Terminal tool window: starts its shell the first time it is shown. */
fun terminalToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(ToolWindow.Terminal, Dock.Bottom),
        tag = TerminalTags.TOOL_BUTTON,
        group = StripGroup.Bottom,
        onShown = { host -> host.service(TERMINAL_SERVICE).ensureStarted() },
        icon = { Label(">_", color = IdeColors.Text, fontWeight = FontWeight.Bold) },
        content = { host, drag, modifier ->
            TerminalPanel(host.service(TERMINAL_SERVICE), host.toolWindows, drag, modifier)
        },
    )

/** Alt+F12 toggles the Terminal window. */
fun terminalShortcuts(): List<KeyBinding<ActionContext>> =
    listOf(binding(setOf(Key.F12), alt = true) { project.toggleToolWindow(ToolWindow.Terminal) })
