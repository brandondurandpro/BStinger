package org.example.kotlineditor.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key

/** Modifiers held when a shortcut fires (Ctrl and Cmd are the same key for the IDE). */
internal class Modifiers(val ctrl: Boolean, val shift: Boolean, val alt: Boolean) {
    constructor(event: KeyEvent) : this(
        event.isCtrlPressed || event.isMetaPressed,
        event.isShiftPressed,
        event.isAltPressed,
    )
}

/** A shortcut: keys plus required modifiers (`null` = either way, the action can read [Modifiers]). */
internal class KeyBinding<C>(
    private val keys: Set<Key>,
    private val ctrl: Boolean?,
    private val shift: Boolean?,
    private val alt: Boolean?,
    val action: C.(Modifiers) -> Unit,
) {
    fun matches(key: Key, held: Modifiers): Boolean =
        key in keys && ctrl.orAny(held.ctrl) && shift.orAny(held.shift) && alt.orAny(held.alt)

    private fun Boolean?.orAny(held: Boolean) = this == null || this == held
}

internal fun <C> binding(
    keys: Set<Key>,
    ctrl: Boolean? = false,
    shift: Boolean? = null,
    alt: Boolean? = false,
    action: C.(Modifiers) -> Unit,
) = KeyBinding(keys, ctrl, shift, alt, action)

/** Runs the first binding matching [event] on [context]; returns whether one did. */
internal fun <C> List<KeyBinding<C>>.dispatch(event: KeyEvent, context: C): Boolean {
    val held = Modifiers(event)
    val match = firstOrNull { it.matches(event.key, held) }
    match?.action?.invoke(context, held)
    return match != null
}
