package org.example.kotlineditor.plugin

import org.example.kotlineditor.state.ProjectHost
import org.example.kotlineditor.ui.KeyBinding
import java.util.concurrent.CopyOnWriteArrayList

/** What a shortcut of a plugin acts on: the open project. */
interface ActionContext {
    /** The project the shortcut was pressed in. */
    val project: ProjectHost
}

/** Where a shortcut applies: [Agent] is agent mode, which hides the editors; [Ide] is the normal workspace. */
enum class ActionScope { Ide, Agent }

/**
 * The shortcuts of the IDE, per [ActionScope], in the order they are tried (the first match wins): the built-in ones,
 * then those of the plugins in the order they loaded. A contribution is removed by closing what [register] returned,
 * which is how a plugin unloading takes its shortcuts away.
 */
class ActionRegistry {
    private class Entry(val scope: ActionScope, val bindings: List<KeyBinding<ActionContext>>)

    private val entries = CopyOnWriteArrayList<Entry>()

    @Volatile
    private var snapshot: Map<ActionScope, List<KeyBinding<ActionContext>>> = emptyMap()

    /** Adds [bindings] after the existing ones of [scope]; closing the result removes them. */
    fun register(scope: ActionScope, bindings: List<KeyBinding<ActionContext>>): AutoCloseable {
        val entry = Entry(scope, bindings)
        entries += entry
        rebuild()
        return AutoCloseable {
            entries -= entry
            rebuild()
        }
    }

    /** The shortcuts of [scope]; a plain list, rebuilt when a contribution comes or goes, never per key press. */
    fun bindings(scope: ActionScope): List<KeyBinding<ActionContext>> = snapshot[scope].orEmpty()

    private fun rebuild() {
        val byScope = entries.groupBy { it.scope }
        snapshot = byScope.mapValues { (_, scoped) -> scoped.flatMap { it.bindings } }
    }
}
