package org.example.kotlineditor.state

/**
 * A dialog opened by a context menu of the Branches window. With an [input] (the initial text) the user types a name;
 * [submit] gets it and tells through its callback whether the action worked, so that a failure stays in the dialog.
 */
class HistoryDialog(
    val title: String,
    val message: String?,
    val input: String?,
    val confirmLabel: String,
    val danger: Boolean = false,
    val submit: (text: String, onDone: (Boolean) -> Unit) -> Unit,
)
