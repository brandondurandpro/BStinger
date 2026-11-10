package org.example.kotlineditor.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults

/** Which group of the strip holds the button of a tool window: the top one, or the bottom one under the spacer. */
internal enum class StripGroup { Top, Bottom }

/** A tool window as a feature contributes it: its defaults, its button and its content. */
@Immutable
internal class ToolWindowContribution(
    val defaults: ToolWindowDefaults,
    /** Test tag of the button. */
    val tag: String,
    val group: StripGroup,
    /** Whether the button only shows once the devtools are on. */
    val devtools: Boolean = false,
    val icon: @Composable () -> Unit,
    val content: @Composable (project: ProjectState, drag: ToolWindowDrag, modifier: Modifier) -> Unit,
) {
    val window: ToolWindow get() = defaults.window
}

/**
 * The tool windows of the IDE, in the order of their buttons. Compose reads [contributions], so a window that is
 * registered or removed (a plugin loading or unloading) appears or disappears without a restart.
 */
internal class ToolWindowRegistry {
    private val registered = mutableStateListOf<ToolWindowContribution>()

    /** The windows, in registration order. */
    val contributions: List<ToolWindowContribution> get() = registered

    /** The contribution of [window], or null once it is unregistered. */
    fun find(window: ToolWindow): ToolWindowContribution? = registered.firstOrNull { it.window == window }

    /** Adds [added]; closing the result removes them. A window registered twice is refused. */
    fun register(added: List<ToolWindowContribution>): AutoCloseable {
        for (contribution in added) {
            require(find(contribution.window) == null) { "Tool window ${contribution.window.name} is registered" }
        }
        registered.addAll(added)
        return AutoCloseable { registered.removeAll(added) }
    }

    companion object {
        /** The registry of the application, with the built-in windows. */
        val default: ToolWindowRegistry = ToolWindowRegistry().apply { register(builtInToolWindows()) }
    }
}
