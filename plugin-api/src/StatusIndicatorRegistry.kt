package org.example.kotlineditor.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import org.example.kotlineditor.state.ProjectHost

/**
 * An indicator of the status bar the user can keep shown or hide, as a feature contributes it. What is wrong
 * (errors, the link) is always shown and is not contributed.
 */
class StatusIndicatorContribution(
    /** Stable id, saved in the preferences; the built-in ones keep the names they had. */
    val key: String,
    /** The name in the menu. */
    val label: String,
    /** The indicator, shown in the status bar while the user keeps it; [project] is the open project. */
    val content: @Composable (project: ProjectHost) -> Unit,
)

/**
 * The indicators of the status bar, in registration order. Compose reads [contributions], so an indicator that is
 * registered or removed (a plugin loading or unloading) appears or disappears without a restart.
 */
class StatusIndicatorRegistry {
    private val registered = mutableStateListOf<StatusIndicatorContribution>()

    /** The indicators, in registration order. */
    val contributions: List<StatusIndicatorContribution> get() = registered

    /** Adds [added]; closing the result removes them. Two indicators of the same key are refused. */
    fun register(added: List<StatusIndicatorContribution>): AutoCloseable {
        for (contribution in added) {
            require(registered.firstOrNull { it.key == contribution.key } == null) {
                "Status indicator ${contribution.key} is registered"
            }
        }
        registered.addAll(added)
        return AutoCloseable { registered.removeAll(added) }
    }
}
