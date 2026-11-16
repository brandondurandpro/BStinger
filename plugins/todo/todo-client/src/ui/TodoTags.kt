package org.example.kotlineditor.ui

/** Test tags of the Todo tool window, shared with the end-to-end tests. Paths are relative to the project root. */
object TodoTags {
    const val TOOL_TODO = "tool.todo"
    const val PANEL = "todo.panel"
    const val REFRESH = "todo.refresh"
    const val HIDE = "todo.hide"
    const val EMPTY = "todo.empty"
    const val LOADING = "todo.loading"
    const val ERROR = "todo.error"

    /** The group of the items of [relativePath] (a header row with the file and its count). */
    fun fileGroup(relativePath: String) = "todo.file:$relativePath"

    /** The item of the marker at [line] (counted from 1) of [relativePath]. */
    fun item(relativePath: String, line: Int) = "todo.item:$relativePath:$line"
}
