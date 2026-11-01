package org.example.kotlineditor.ui

/**
 * Semantics test tags of the editor module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
@Suppress("TooManyFunctions")
object EditorTags {
    const val DIFF = "diff"
    const val DIFF_TOOLBAR = "diff.toolbar"
    const val DIFF_OPEN_FILE = "diff.openFile"
    const val DIFF_LAYOUT_TOGGLE = "diff.layoutToggle"
    const val DIFF_SPLIT = "diff.split"
    const val TABS = "tabs"
    const val TAB_MENU = "tab.menu"
    const val MENU_SPLIT_RIGHT = "menu.splitRight"
    const val MENU_UNSPLIT = "menu.unsplit"
    const val MENU_COPY_TAB_PATH = "menu.copyTabPath"
    const val EDITOR = "editor"
    const val EDITOR_TEXT = "editor.text"
    const val EDITOR_VERTICAL_SCROLLBAR = "editor.vscroll"
    const val EDITOR_MESSAGE = "editor.message"
    const val COMPLETION = "completion"
    const val FIND_BAR = "findBar"
    const val FIND_BAR_INPUT = "findBar.input"
    const val FIND_BAR_COUNT = "findBar.count"
    const val FIND_BAR_NEXT = "findBar.next"
    const val FIND_BAR_PREVIOUS = "findBar.previous"
    const val FIND_BAR_CLOSE = "findBar.close"
    const val REPLACE_INPUT = "replace.input"
    const val REPLACE_ONE = "replace.one"
    const val REPLACE_ALL = "replace.all"

    fun tab(relativePath: String) = "tab:$relativePath"

    fun tabClose(relativePath: String) = "tab.close:$relativePath"

    fun tabModified(relativePath: String) = "tab.modified:$relativePath"

    fun diffTab(relativePath: String) = "tab.diff:$relativePath"

    fun diffTabClose(relativePath: String) = "tab.diff.close:$relativePath"

    fun commitDiffTab(hash: String, relativePath: String) = "tab.commitDiff:$hash:$relativePath"

    fun commitDiffTabClose(hash: String, relativePath: String) = "tab.commitDiff.close:$hash:$relativePath"

    fun diffLine(index: Int) = "diff.line:$index"

    fun diffLineKind(index: Int, kind: String) = "diff.line:$index:$kind"

    fun diffSplitRow(index: Int) = "diff.split.row:$index"

    fun diffSplitCell(index: Int, side: Int) = "diff.split.cell:$index:$side"

    fun changeMarker(line: Int, kind: String) = "editor.change:$line:$kind"

    fun blame(line: Int) = "editor.blame:$line"

    fun line(index: Int) = "line:$index"

    /** A row of the completion popup, by its label. */
    fun completionItem(label: String) = "completion.item:$label"

    /** The text of a line of the editor, as wide as the text (without the gutter). */
    fun lineText(index: Int) = "line.text:$index"

    fun foldMarker(line: Int) = "fold:$line"
}
