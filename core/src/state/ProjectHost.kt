package org.example.kotlineditor.state

import org.example.kotlineditor.model.FileEntry

/** What the UI of a feature needs from the open project; `ProjectState` implements it in `shared`. */
interface ProjectHost {
    val root: FileEntry
    val toolWindows: ToolWindowLayout

    /** Shows [window] if hidden, starting what it needs (terminal session, Git status...). */
    fun showToolWindow(window: ToolWindow)
}
