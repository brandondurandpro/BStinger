package org.example.kotlineditor.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import org.example.kotlineditor.state.ProjectHost

/** A button of the toolbar of the window, as a feature contributes it. */
class ToolbarContribution(
    /** Where it sits among the toolbar's, before the ones ranked after it. */
    val rank: Int,
    /** The button, and what is around it; [project] is the open project. */
    val content: @Composable (project: ProjectHost) -> Unit,
)

/**
 * The buttons of the toolbar of the window, in the order of their rank. Compose reads [contributions], so a button
 * that is registered or removed (a plugin loading or unloading) appears or disappears without a restart.
 */
class ToolbarRegistry {
    private val registered = mutableStateListOf<ToolbarContribution>()

    /** The buttons, in the order of their rank. */
    val contributions: List<ToolbarContribution> get() = registered.sortedBy { it.rank }

    /** Adds [added]; closing the result removes them. */
    fun register(added: List<ToolbarContribution>): AutoCloseable {
        registered.addAll(added)
        return AutoCloseable { registered.removeAll(added) }
    }
}
