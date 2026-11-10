package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.ActionRegistry
import org.example.kotlineditor.plugin.ActionScope
import org.example.kotlineditor.plugin.IdePlugin
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

// The buttons keep the order they always had: Project, Git and Branches on top; Run, devtools, language servers and
// Terminal at the bottom.
private fun allToolWindows(): List<ToolWindowContribution> {
    val own = builtInToolWindows().associateBy { it.window }
    val bundled =
        listOf(
            projectToolWindow(),
            gitToolWindow(),
            branchesToolWindow(),
            runToolWindow(),
            languageServersToolWindow(),
            terminalToolWindow(),
        )
    val byWindow = own + bundled.associateBy { it.window }
    val order =
        listOf(
            ToolWindow.Project,
            ToolWindow.Git,
            ToolWindow.Branches,
            ToolWindow.Run,
            ToolWindow.Network,
            ToolWindow.LanguageServers,
            ToolWindow.Terminal,
        )
    return order.mapNotNull { byWindow[it] }
}

private fun bundledShortcuts() = projectShortcuts() + runShortcuts() + gitShortcuts() + terminalShortcuts()

/**
 * Loads [plugin] into the running IDE: its tool windows and shortcuts appear at once. Closing the result unloads it,
 * and takes everything it contributed away.
 */
fun loadPlugin(plugin: IdePlugin): AutoCloseable {
    val windows = IdeRegistries.toolWindows.register(plugin.toolWindows)
    val shortcuts = IdeRegistries.actions.register(ActionScope.Ide, plugin.shortcuts)
    return AutoCloseable {
        shortcuts.close()
        windows.close()
    }
}
