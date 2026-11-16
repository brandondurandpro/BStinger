package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin

/** The client half of the bundled Terminal feature: its tool window and its shortcuts. */
class TerminalClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(terminalToolWindow())
        terminalShortcuts().forEach(contributions::shortcut)
    }
}
