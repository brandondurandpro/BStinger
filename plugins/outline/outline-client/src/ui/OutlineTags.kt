package org.example.kotlineditor.ui

/** Semantics test tags of the outline module, shared with the end-to-end tests. */
object OutlineTags {
    const val TOOL_BUTTON = "tool.outline"
    const val PANEL = "outline.panel"
    const val LOADING = "outline.loading"
    const val EMPTY = "outline.empty"
    const val NO_FILE = "outline.noFile"

    /** A declaration row, by its name and its zero-based line in the file. */
    fun item(name: String, line: Int) = "outline.item:$name:$line"
}
