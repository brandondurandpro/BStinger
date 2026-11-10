package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.ActionRegistry
import org.example.kotlineditor.plugin.ActionScope
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.plugin.ToolWindowRegistry
import org.example.kotlineditor.state.ToolWindow

/**
 * What the IDE client is made of: the registries the UI reads, filled with the bundled features. A plugin that loads
 * registers in them too, and closes its registration when it unloads.
 */
internal object IdeRegistries {
    /** Tool windows, in the order of their buttons. */
    val toolWindows: ToolWindowRegistry =
        ToolWindowRegistry().apply { register(allToolWindows()) }

    /** Shortcuts, the IDE's own first. */
    val actions: ActionRegistry =
        ActionRegistry().apply {
            register(ActionScope.Ide, BUILT_IN_IDE_SHORTCUTS.map { it.asAction() })
            register(ActionScope.Agent, BUILT_IN_AGENT_SHORTCUTS.map { it.asAction() })
            register(ActionScope.Ide, bundledShortcuts())
        }
}

// Every context of the IDE is an IdeContext: the registry only knows the part a plugin sees.
private fun KeyBinding<IdeContext>.asAction(): KeyBinding<ActionContext> = adapt { context -> context as IdeContext }

// Run sits first of the bottom group, before the devtools and the language servers.
private fun allToolWindows(): List<ToolWindowContribution> {
    val windows = builtInToolWindows().toMutableList()
    val network = windows.indexOfFirst { it.window == ToolWindow.Network }
    windows.add(if (network < 0) windows.size else network, runToolWindow())
    return windows
}

private fun bundledShortcuts() = runShortcuts()
