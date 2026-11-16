package org.example.kotlineditor.ui

/** Test tags of the EditorConfig tool window, shared with the end-to-end tests. Paths are relative to the root. */
object EditorConfigTags {
    const val TOOL = "tool.editorconfig"
    const val PANEL = "editorconfig.panel"
    const val REFRESH = "editorconfig.refresh"
    const val HIDE = "editorconfig.hide"
    const val EMPTY = "editorconfig.empty"
    const val LOADING = "editorconfig.loading"
    const val ERROR = "editorconfig.error"

    /** The group of the violations of [relativePath] (a header row with the file and their count). */
    fun fileGroup(relativePath: String) = "editorconfig.file:$relativePath"

    /** The item of the violation of [rule] at [line] (counted from 1, 0 for the whole file) of [relativePath]. */
    fun item(relativePath: String, line: Int, rule: String) = "editorconfig.item:$relativePath:$line:$rule"
}
