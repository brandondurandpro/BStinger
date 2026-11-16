package org.example.kotlineditor.plugin

import org.example.kotlineditor.ui.KeyBinding

/** What a plugin adds to the client: the contributions its `register` would make, as data. */
class IdePlugin(
    /** Unique id of the plugin. */
    val id: String,
    /** Its tool windows. */
    val toolWindows: List<ToolWindowContribution> = emptyList(),
    /** Its shortcuts of the normal workspace. */
    val shortcuts: List<KeyBinding<ActionContext>> = emptyList(),
    /** Its buttons of the toolbar of the window. */
    val toolbarButtons: List<ToolbarContribution> = emptyList(),
    /** Its dialogs shown over the project. */
    val dialogs: List<DialogContribution> = emptyList(),
    /** Its indicators of the status bar. */
    val statusIndicators: List<StatusIndicatorContribution> = emptyList(),
)
