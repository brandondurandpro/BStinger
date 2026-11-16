package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin

/** The client half of the bundled Git feature: its tool windows and its shortcuts. */
class GitClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(gitToolWindow())
        contributions.toolWindow(branchesToolWindow())
        gitShortcuts().forEach(contributions::shortcut)
    }
}
