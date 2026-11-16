package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin

/** The client half of the Extensions window: the plugins installed and the servers offered, to turn on or off. */
class ExtensionsClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.toolWindow(extensionsToolWindow())
    }
}
