package org.example.kotlineditor.ui

import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.ActionRegistry
import org.example.kotlineditor.plugin.ActionScope
import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.DialogRegistry
import org.example.kotlineditor.plugin.IdePlugin
import org.example.kotlineditor.plugin.StatusIndicatorRegistry
import org.example.kotlineditor.plugin.ToolWindowRegistry
import org.example.kotlineditor.plugin.ToolbarRegistry
import java.util.ServiceLoader

/**
 * What the IDE client is made of: the registries the UI reads. The bundled features contribute to them through
 * their [ClientPlugin]s (found with `ServiceLoader`, like the server finds its halves), a plugin that loads
 * registers in them too, and closing its registration takes everything it contributed away.
 */
internal object IdeRegistries {
    /** Tool windows, the bundled ones ranked by their place in the strip. */
    val toolWindows: ToolWindowRegistry =
        ToolWindowRegistry().apply { register(bundled.flatMap { it.toolWindows }.sortedBy { it.rank }) }

    /** Shortcuts, the IDE's own first. */
    val actions: ActionRegistry =
        ActionRegistry().apply {
            register(ActionScope.Ide, BUILT_IN_IDE_SHORTCUTS.map { it.asAction() })
            register(ActionScope.Agent, BUILT_IN_AGENT_SHORTCUTS.map { it.asAction() })
            register(ActionScope.Ide, bundled.flatMap { it.shortcuts })
        }

    /** The buttons of the toolbar of the window, in the order of their rank. */
    val toolbar: ToolbarRegistry =
        ToolbarRegistry().apply { register(bundled.flatMap { it.toolbarButtons }) }

    /** The dialogs the features show over the project. */
    val dialogs: DialogRegistry =
        DialogRegistry().apply { register(bundled.flatMap { it.dialogs }) }

    /** The indicators of the status bar. */
    val statusIndicators: StatusIndicatorRegistry =
        StatusIndicatorRegistry().apply { register(bundled.flatMap { it.statusIndicators }) }
}

// Every context of the IDE is an IdeContext: the registry only knows the part a plugin sees.
private fun KeyBinding<IdeContext>.asAction(): KeyBinding<ActionContext> = adapt { context -> context as IdeContext }

// The client halves bundled with the IDE, loaded once (they are stateless: every register call would give the same
// contributions).
private val bundled: List<IdePlugin> by lazy {
    val plugins =
        ServiceLoader
            .load(ClientPlugin::class.java)
            .map { plugin ->
                val contributions = ClientContributions()
                plugin.register(contributions)
                contributions.toPlugin(plugin.javaClass.simpleName)
            }
    plugins.toList()
}

/**
 * Loads [plugin] into the running IDE: its tool windows and shortcuts appear at once. Closing the result unloads it,
 * and takes everything it contributed away.
 */
fun loadPlugin(plugin: IdePlugin): AutoCloseable {
    val windows = IdeRegistries.toolWindows.register(plugin.toolWindows)
    val shortcuts = IdeRegistries.actions.register(ActionScope.Ide, plugin.shortcuts)
    val toolbar = IdeRegistries.toolbar.register(plugin.toolbarButtons)
    val dialogs = IdeRegistries.dialogs.register(plugin.dialogs)
    val indicators = IdeRegistries.statusIndicators.register(plugin.statusIndicators)
    return AutoCloseable {
        indicators.close()
        dialogs.close()
        toolbar.close()
        shortcuts.close()
        windows.close()
    }
}
