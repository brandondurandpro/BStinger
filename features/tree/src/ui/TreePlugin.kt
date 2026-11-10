package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.state.TreeHost

/** The Project tool window: the file tree, shown at the start in the left dock. */
fun projectToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(ToolWindow.Project, Dock.Left, shown = true),
        tag = TreeTags.TOOL_BUTTON,
        group = StripGroup.Top,
        icon = { FolderIcon(Modifier.size(18.dp)) },
        content = { host, drag, modifier -> ProjectPanel(host as TreeHost, drag, modifier) },
    )

/** Alt+1 toggles the Project window. */
fun projectShortcuts(): List<KeyBinding<ActionContext>> =
    listOf(binding(setOf(Key.One), alt = true) { project.toggleToolWindow(ToolWindow.Project) })
