package org.example.kotlineditor.state

// Bulk close actions of the terminal tabs' context menu.

/** Closes every tab but the one at [index] ("Close Others"), and shows it. */
fun TerminalState.closeOthers(index: Int) = closeAround(index) { it != index }

/** Closes the tabs left of [index] ("Close All to the Left"), and shows the one at [index]. */
fun TerminalState.closeToLeft(index: Int) = closeAround(index) { it < index }

/** Closes the tabs right of [index] ("Close All to the Right"), and shows the one at [index]. */
fun TerminalState.closeToRight(index: Int) = closeAround(index) { it > index }
