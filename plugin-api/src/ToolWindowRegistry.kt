package org.example.kotlineditor.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Modifier
import org.example.kotlineditor.state.ProjectHost
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.ui.LucideIcon
import org.example.kotlineditor.ui.ToolWindowDrag

/** Which group of the strip holds the button of a tool window: the top one, or the bottom one under the spacer. */
enum class StripGroup { Top, Bottom }

/** A tool window as a feature contributes it: its defaults, its button and its content. */
@Immutable
class ToolWindowContribution(
    /** Id, dock and visibility until the user arranges it. */
    val defaults: ToolWindowDefaults,
    /** Test tag of the button. */
    val tag: String,
    /** Where the button sits in the strip. */
    val group: StripGroup,
    /** Whether the button only shows once the devtools are on. */
    val devtools: Boolean = false,
    /** Runs when the window is shown, to start what it needs (load the configurations, start a shell...). */
    val onShown: (ProjectHost) -> Unit = {},
    /** The icon of the button. */
    val icon: LucideIcon,
    /** The panel; [Modifier] sizes it in its dock. */
    val content: @Composable (project: ProjectHost, drag: ToolWindowDrag, modifier: Modifier) -> Unit,
) {
    /** The window this contributes. */
    val window: ToolWindow get() = defaults.window
}

/**
 * The tool windows of the IDE, in the order of their buttons. Compose reads [contributions], so a window that is
 * registered or removed (a plugin loading or unloading) appears or disappears without a restart.
 */
class ToolWindowRegistry {
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
}
