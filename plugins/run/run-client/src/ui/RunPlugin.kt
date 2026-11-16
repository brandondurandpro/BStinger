package org.example.kotlineditor.ui

import androidx.compose.ui.input.key.Key
import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.plugin.ToolbarContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.RunState
import org.example.kotlineditor.state.ServiceKey
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults

/** The Run state of a project, as [org.example.kotlineditor.state.ProjectHost.service] gives it to the Run feature. */
val RUN_SERVICE = ServiceKey<RunState>("run")

/** The Run tool window: its button in the bottom group of the strip, its panel, and the loading it needs when shown. */
fun runToolWindow(): ToolWindowContribution =
    ToolWindowContribution(
        defaults = ToolWindowDefaults(ToolWindow.Run, Dock.Bottom),
        tag = RunTags.TOOL_BUTTON,
        group = StripGroup.Bottom,
        rank = 5,
        onShown = { host -> host.service(RUN_SERVICE).ensureLoaded() },
        icon = Lucide.Play,
        content = { host, drag, modifier ->
            RunPanel(host.service(RUN_SERVICE), host.toolWindows, drag, modifier)
        },
    )

/** The widget of the toolbar: the selected configuration, to run or stop. */
fun runToolbarButton(): ToolbarContribution =
    ToolbarContribution(rank = 0) { project ->
        RunWidget(project.service(RUN_SERVICE), { project.showToolWindow(ToolWindow.Run) })
    }

/** Alt+4 toggles the window, Shift+F10 runs the selected configuration, Ctrl+F2 stops it. */
fun runShortcuts(): List<KeyBinding<ActionContext>> =
    listOf(
        binding(setOf(Key.Four), alt = true) { project.toggleToolWindow(ToolWindow.Run) },
        binding(setOf(Key.F10), shift = true) {
            project.showToolWindow(ToolWindow.Run)
            project.service(RUN_SERVICE).runSelected()
        },
        binding(setOf(Key.F2), ctrl = true) { project.service(RUN_SERVICE).stop() },
    )
