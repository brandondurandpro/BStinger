package org.example.kotlineditor.ui

/**
 * Semantics test tags of the editor module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
@Suppress("TooManyFunctions")
object EditorTags {
    const val DIFF = "diff"

    /** In the diff once the grammar of its file colored it (the line lexer colors it until then). */
    const val DIFF_SYNTAX = "diff.syntax"
    const val DIFF_TOOLBAR = "diff.toolbar"
    const val DIFF_SIDES = "diff.sides"
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
    const val MENU_COPY_TAB_RELATIVE_PATH = "menu.copyTabRelativePath"
    const val EDITOR = "editor"
    const val EDITOR_TEXT = "editor.text"
    const val EDITOR_VERTICAL_SCROLLBAR = "editor.vscroll"
    const val EDITOR_MESSAGE = "editor.message"
    const val DISK_CONFLICT = "editor.diskConflict"
    const val DISK_COMPARE = "editor.diskCompare"
    const val DISK_RELOAD = "editor.diskReload"
    const val DISK_KEEP = "editor.diskKeep"
    const val SAVE_ERROR = "editor.saveError"
    const val COMPLETION = "completion"
    const val COMPLETION_DOCUMENTATION = "completion.documentation"
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
    const val PROBLEMS_LIST = "problems.list"
    const val PROBLEMS_LIST_EMPTY = "problems.list.empty"
    const val PROBLEMS_LIST_TITLE = "problems.list.title"
    const val INLAY_HINTS_POPUP = "inlayHints.popup"
    const val HIERARCHY = "hierarchy"
    const val HIERARCHY_SCRIM = "hierarchy.scrim"
    const val HIERARCHY_TITLE = "hierarchy.title"
    const val HIERARCHY_SWITCH = "hierarchy.switch"
    const val HIERARCHY_LOADING = "hierarchy.loading"
    const val HIERARCHY_FAILED = "hierarchy.failed"
    const val HIERARCHY_EMPTY = "hierarchy.empty"
    const val PROBLEMS_SCRIM = "problems.scrim"

    const val MERGE = "merge"
    const val MERGE_TOOLBAR = "merge.toolbar"
    const val MERGE_STATUS = "merge.status"
    const val MERGE_NEXT = "merge.next"
    const val MERGE_ALL_OURS = "merge.allOurs"
    const val MERGE_ALL_THEIRS = "merge.allTheirs"
    const val MERGE_APPLY = "merge.apply"
    const val MERGE_CANCEL = "merge.cancel"
    const val MERGE_LIST = "merge.list"
    const val MERGE_HISTORY_TOGGLE = "merge.historyToggle"
    const val MERGE_HISTORY = "merge.history"

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

    /** The compare of a conflict: the editor's content against what the disk holds. */
    fun compareTab(relativePath: String) = "tab.compare:$relativePath"

    fun compareTabClose(relativePath: String) = "tab.compare.close:$relativePath"

    /** The three-way merge of a conflicted file. */
    fun mergeTab(relativePath: String) = "tab.merge:$relativePath"

    fun mergeTabClose(relativePath: String) = "tab.merge.close:$relativePath"

    /** The decision buttons of the stretch [chunk]: `Ours`, `Theirs`, `Both` or `Base`. */
    fun mergeChoice(chunk: Int, choice: String) = "merge.choice:$chunk:$choice"

    /** A commit of the history of the file, on [side] (`ours` or `theirs`). */
    fun mergeHistoryCommit(side: String, hash: String) = "merge.history.$side:$hash"

    /** The heading of the changed stretch [chunk]. */
    fun mergeHeading(chunk: Int) = "merge.heading:$chunk"

    /** Row [index] of the three columns. */
    fun mergeRow(index: Int) = "merge.row:$index"

    /** The cell of [column] (`0` ours, `1` result, `2` theirs) in row [index]. */
    fun mergeCell(index: Int, column: Int) = "merge.cell:$index:$column"

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

    /** The file and line of row [index] of the list of places. */
    fun locationFile(index: Int) = "locations.file:$index"

    /** The directory of row [index] of the list of places. */
    fun locationDirectory(index: Int) = "locations.directory:$index"

    /** The code of row [index] of the list of places. */
    fun locationPreview(index: Int) = "locations.preview:$index"

    /** A row of the quick fixes popup, by its title. */
    fun codeAction(title: String) = "codeActions.item:$title"

    /** A problem underlined on [line], by severity (`Error`, `Warning`...). */
    fun diagnostic(line: Int, severity: String) = "editor.diagnostic:$line:$severity"

    /** Code lens [at] (from the left) at the end of [line]. */
    fun codeLens(line: Int, at: Int) = "editor.codeLens:$line:$at"

    /** The "3 usages" hint at the end of [line]. */
    fun usageHint(line: Int) = "editor.usages:$line"

    /** An occurrence of the symbol at the caret highlighted by the language server, on [line]. */
    fun symbolHighlight(line: Int, kind: String) = "editor.highlight:$line:$kind"

    /** Row [index] of the call or type hierarchy. */
    fun hierarchyRow(index: Int) = "hierarchy.row:$index"

    /** The arrow that expands or collapses row [index] of the hierarchy. */
    fun hierarchyToggle(index: Int) = "hierarchy.toggle:$index"

    /** The inlay hint drawn before the character at [column] of [line]. */
    fun inlayHint(line: Int, column: Int) = "inlayHint:$line:$column"

    /** Row [index] of the problems list. */
    fun problem(index: Int) = "problems.list:$index"

    /** The "go to" arrow of row [index] of the problems list. */
    fun problemGo(index: Int) = "problems.go:$index"
}
