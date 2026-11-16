package org.example.kotlineditor.plugin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import org.example.kotlineditor.state.ProjectHost

/** A dialog a feature shows over the project when its state says so, as it contributes it. */
class DialogContribution(
    /** The dialog, shown over the editors while the feature wants it; [project] is the open project. */
    val content: @Composable (project: ProjectHost) -> Unit,
)

/**
 * The dialogs the features show over the project, in registration order. Compose reads [contributions], so a
 * dialog that is registered or removed (a plugin loading or unloading) appears or disappears without a restart.
 */
class DialogRegistry {
    private val registered = mutableStateListOf<DialogContribution>()

    /** The dialogs, in registration order. */
    val contributions: List<DialogContribution> get() = registered

    /** Adds [added]; closing the result removes them. */
    fun register(added: List<DialogContribution>): AutoCloseable {
        registered.addAll(added)
        return AutoCloseable { registered.removeAll(added) }
    }
}
