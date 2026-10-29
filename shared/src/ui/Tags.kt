package org.example.kotlineditor.ui

/** Semantics test tags, shared by the UI and the end-to-end tests. Paths are relative to the project root. */
@Suppress("TooManyFunctions")
object Tags {
    const val WELCOME = "welcome"
    const val WELCOME_OPEN = "welcome.open"
    const val WELCOME_SEARCH = "welcome.search"
    const val WELCOME_ERROR = "welcome.error"
    const val PROJECT = "project"
    const val PROJECT_WIDGET = "project.widget"
    const val PROJECT_CLOSE = "project.close"
    const val PROJECT_OPEN_OTHER = "project.openOther"
    const val GIT_BRANCH = "git.branch"
    const val PROJECT_PANEL = "project.panel"
    const val GIT_PANEL = "git.panel"
    const val GIT_EMPTY = "git.empty"
    const val GIT_NOT_A_REPOSITORY = "git.notRepository"
    const val GIT_REFRESH = "git.refresh"
    const val GIT_HIDE = "git.hide"
    const val TOOL_PROJECT = "tool.project"
    const val TOOL_GIT = "tool.git"
    const val TOOL_TERMINAL = "tool.terminal"
    const val WORKSPACE = "workspace"
    const val DIFF = "diff"
    const val TERMINAL = "terminal"
    const val TERMINAL_INPUT = "terminal.input"
    const val TERMINAL_CLEAR = "terminal.clear"
    const val TERMINAL_HIDE = "terminal.hide"
    const val TREE = "tree"
    const val TREE_MENU = "tree.menu"
    const val TREE_AUTO_SELECT = "tree.autoSelect"
    const val MENU_NEW_FILE = "menu.newFile"
    const val MENU_NEW_DIRECTORY = "menu.newDirectory"
    const val MENU_RENAME = "menu.rename"
    const val MENU_DELETE = "menu.delete"
    const val MENU_COPY_PATH = "menu.copyPath"
    const val MENU_RELOAD = "menu.reload"
    const val FILE_DIALOG = "fileDialog"
    const val FILE_DIALOG_INPUT = "fileDialog.input"
    const val FILE_DIALOG_OK = "fileDialog.ok"
    const val FILE_DIALOG_ERROR = "fileDialog.error"
    const val TABS = "tabs"
    const val EDITOR = "editor"
    const val EDITOR_TEXT = "editor.text"
    const val EDITOR_VERTICAL_SCROLLBAR = "editor.vscroll"
    const val EDITOR_EMPTY = "editor.empty"
    const val EDITOR_MESSAGE = "editor.message"
    const val EDITOR_LOADING = "editor.loading"
    const val STATUS_BAR = "status"
    const val STATUS_CARET = "status.caret"
    const val STATUS_PATH = "status.path"
    const val STATUS_CONNECTION = "status.connection"
    const val GOTO_FILE = "gotoFile"
    const val RECENT_FILES = "recentFiles"
    const val GOTO_FILE_INPUT = "gotoFile.input"
    const val FIND_IN_FILES = "findInFiles"
    const val FIND_IN_FILES_INPUT = "findInFiles.input"
    const val FIND_IN_FILES_COUNT = "findInFiles.count"
    const val FIND_BAR = "findBar"
    const val FIND_BAR_INPUT = "findBar.input"
    const val FIND_BAR_COUNT = "findBar.count"
    const val FIND_BAR_NEXT = "findBar.next"
    const val FIND_BAR_PREVIOUS = "findBar.previous"
    const val FIND_BAR_CLOSE = "findBar.close"
    const val REPLACE_INPUT = "replace.input"
    const val REPLACE_ONE = "replace.one"
    const val REPLACE_ALL = "replace.all"
    const val GOTO_SYMBOL = "gotoSymbol"
    const val GOTO_SYMBOL_INPUT = "gotoSymbol.input"
    const val GOTO_LINE = "gotoLine"
    const val STRUCTURE = "structure"
    const val STRUCTURE_INPUT = "structure.input"
    const val GOTO_LINE_INPUT = "gotoLine.input"

    fun treeRow(relativePath: String) = "tree:$relativePath"

    fun treeChevron(relativePath: String) = "tree.chevron:$relativePath"

    fun treeGitStatus(relativePath: String, status: String) = "tree.git:$relativePath:$status"

    fun tab(relativePath: String) = "tab:$relativePath"

    fun tabClose(relativePath: String) = "tab.close:$relativePath"

    fun tabModified(relativePath: String) = "tab.modified:$relativePath"

    fun diffTab(relativePath: String) = "tab.diff:$relativePath"

    fun diffTabClose(relativePath: String) = "tab.diff.close:$relativePath"

    fun diffLine(index: Int) = "diff.line:$index"

    fun diffLineKind(index: Int, kind: String) = "diff.line:$index:$kind"

    fun toolHeader(window: String) = "tool.header:$window"

    fun dockSplitter(dock: String) = "dock.splitter:$dock"

    fun line(index: Int) = "line:$index"

    fun foldMarker(line: Int) = "fold:$line"
}

/** Test tags of the items of lists and popups (recent projects, search results, terminal lines). */
object ItemTags {
    fun recentProject(path: String) = "recent:$path"

    fun recentRemove(path: String) = "recent.remove:$path"

    fun terminalLine(index: Int) = "terminal.line:$index"

    fun gitChange(relativePath: String) = "git.change:$relativePath"

    fun gotoFileItem(relativePath: String) = "gotoFile.item:$relativePath"

    fun findInFilesItem(relativePath: String, line: Int) = "findInFiles.item:$relativePath:$line"

    fun gotoSymbolItem(name: String, relativePath: String) = "gotoSymbol.item:$name:$relativePath"

    fun structureItem(name: String, line: Int) = "structure.item:$name:$line"
}
