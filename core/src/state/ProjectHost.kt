package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.PluginServer

/** What the UI of a feature needs from the open project; `ProjectState` implements it in `shared`. */
interface ProjectHost {
    val root: FileEntry
    val toolWindows: ToolWindowLayout

    /** The methods the server halves of plugins answer. */
    val pluginServer: PluginServer

    /** Shows [window] if hidden, starting what it needs (terminal session, Git status...). */
    fun showToolWindow(window: ToolWindow)

    /** Shows [window] if hidden, hides it if shown, starting what it needs when it appears. */
    fun toggleToolWindow(window: ToolWindow)

    /** The state a feature keeps in the project, published under [key]; fails for a feature that is not loaded. */
    fun <T : Any> service(key: ServiceKey<T>): T
}

/** Names the state a feature keeps in the project, so that another module reaches it through [ProjectHost.service]. */
class ServiceKey<T : Any>(
    /** For messages. */
    val name: String,
)
