package org.example.kotlineditor.ui

/**
 * Semantics test tags of the editor module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
@Suppress("TooManyFunctions")
object EditorTags {
    const val DIFF = "diff"
    const val DIFF_TOOLBAR = "diff.toolbar"
    const val DIFF_COMMIT = "diff.commit"
    const val DIFF_COMMIT_HASH = "diff.commit.hash"
    const val DIFF_COMMIT_AUTHOR = "diff.commit.author"
    const val DIFF_COMMIT_MESSAGE = "diff.commit.message"
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
    const val HOVER = "hover"
    const val HOVER_DOCUMENTATION = "hover.documentation"
    const val HOVER_PROBLEMS = "hover.problems"
    const val LOCATIONS = "locations"
    const val LOCATIONS_TITLE = "locations.title"
    const val SIGNATURE = "signature"
    const val SIGNATURE_ACTIVE_PARAMETER = "signature.activeParameter"
    const val RENAME = "rename"
    const val RENAME_INPUT = "rename.input"
    const val CODE_ACTIONS = "codeActions"
    const val NOTICE = "notice"
    const val PROBLEMS = "problems"
    const val PROBLEMS_ERRORS = "problems.errors"
    const val PROBLEMS_WARNINGS = "problems.warnings"
    const val LINK = "editor.link"
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

    /** A row of the list of places (usages, declarations), by its index. */
    fun location(index: Int) = "locations.item:$index"

    /** A row of the quick fixes popup, by its title. */
    fun codeAction(title: String) = "codeActions.item:$title"

    /** A problem underlined on [line], by severity (`Error`, `Warning`...). */
    fun diagnostic(line: Int, severity: String) = "editor.diagnostic:$line:$severity"

    /** The "3 usages" hint at the end of [line]. */
    fun usageHint(line: Int) = "editor.usages:$line"

    /** An occurrence of the symbol at the caret highlighted by the language server, on [line]. */
    fun symbolHighlight(line: Int, kind: String) = "editor.highlight:$line:$kind"
}
