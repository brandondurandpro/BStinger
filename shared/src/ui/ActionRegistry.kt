package org.example.kotlineditor.ui

import java.util.concurrent.CopyOnWriteArrayList

/** Where a shortcut applies: [Agent] is agent mode, which hides the editors; [Ide] is the normal workspace. */
internal enum class ActionScope { Ide, Agent }

/**
 * The shortcuts of the IDE, per [ActionScope], in the order they are tried (the first match wins): the built-in ones,
 * then those of the plugins in the order they loaded. A contribution is removed by closing what [register] returned,
 * which is how a plugin unloading takes its shortcuts away.
 */
internal class ActionRegistry {
    private class Entry(val scope: ActionScope, val bindings: List<KeyBinding<IdeContext>>)

    private val entries = CopyOnWriteArrayList<Entry>()

    @Volatile
    private var snapshot: Map<ActionScope, List<KeyBinding<IdeContext>>> = emptyMap()

    /** Adds [bindings] after the existing ones of [scope]; closing the result removes them. */
    fun register(scope: ActionScope, bindings: List<KeyBinding<IdeContext>>): AutoCloseable {
        val entry = Entry(scope, bindings)
        entries += entry
        rebuild()
        return AutoCloseable {
            entries -= entry
            rebuild()
        }
    }

    /** The shortcuts of [scope]; a plain list, rebuilt when a contribution comes or goes, never per key press. */
    fun bindings(scope: ActionScope): List<KeyBinding<IdeContext>> = snapshot[scope].orEmpty()

    private fun rebuild() {
        val byScope = entries.groupBy { it.scope }
        snapshot = byScope.mapValues { (_, scoped) -> scoped.flatMap { it.bindings } }
    }

    companion object {
        /** The registry of the application, with the built-in shortcuts. */
        val default: ActionRegistry =
            ActionRegistry().apply {
                register(ActionScope.Ide, BUILT_IN_IDE_SHORTCUTS)
                register(ActionScope.Agent, BUILT_IN_AGENT_SHORTCUTS)
            }
    }
}
