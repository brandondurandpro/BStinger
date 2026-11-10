package org.example.kotlineditor.ui

/**
 * Semantics test tags of the tree module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
object TreeTags {
    const val PROJECT_PANEL = "project.panel"
    const val TREE = "tree"
    const val TREE_MENU = "tree.menu"
    const val TREE_AUTO_SELECT = "tree.autoSelect"
    const val MENU_NEW_FILE = "menu.newFile"
    const val MENU_NEW_DIRECTORY = "menu.newDirectory"
    const val MENU_RENAME = "menu.rename"
    const val MENU_DELETE = "menu.delete"
    const val MENU_DEPLOY = "menu.deploy"
    const val MENU_COPY_PATH = "menu.copyPath"
    const val MENU_RELOAD = "menu.reload"
    const val GOTO_FILE = "gotoFile"
    const val RECENT_FILES = "recentFiles"
    const val GOTO_FILE_INPUT = "gotoFile.input"
    const val FIND_IN_FILES = "findInFiles"
    const val FIND_IN_FILES_INPUT = "findInFiles.input"
    const val FIND_IN_FILES_COUNT = "findInFiles.count"
    const val FIND_IN_FILES_ENGINE = "findInFiles.engine"
    const val FIND_IN_FILES_CASE = "findInFiles.case"
    const val FIND_IN_FILES_WHOLE_WORD = "findInFiles.wholeWord"
    const val FIND_IN_FILES_REGEX = "findInFiles.regex"
    const val FIND_IN_FILES_GLOB_BUTTON = "findInFiles.glob"
    const val FIND_IN_FILES_GLOB_INPUT = "findInFiles.globInput"
    const val FIND_IN_FILES_INCLUDE_IGNORED = "findInFiles.includeIgnored"
    const val FIND_IN_FILES_SCOPE_BUTTON = "findInFiles.scope"
    const val FIND_IN_FILES_SCOPE_MENU = "findInFiles.scopeMenu"
    const val FIND_IN_FILES_ERROR = "findInFiles.error"
    const val GOTO_SYMBOL = "gotoSymbol"
    const val GOTO_SYMBOL_INPUT = "gotoSymbol.input"
    const val STRUCTURE = "structure"
    const val STRUCTURE_INPUT = "structure.input"
    const val STRUCTURE_LOADING = "structure.loading"
    const val GOTO_SYMBOL_SEARCHING = "gotoSymbol.searching"

    fun treeRow(relativePath: String) = "tree:$relativePath"

    fun treeSticky(relativePath: String) = "tree.sticky:$relativePath"

    fun treeChevron(relativePath: String) = "tree.chevron:$relativePath"

    fun treeGitStatus(relativePath: String, status: String) = "tree.git:$relativePath:$status"

    fun gotoFileItem(relativePath: String) = "gotoFile.item:$relativePath"

    fun findInFilesItem(relativePath: String, line: Int) = "findInFiles.item:$relativePath:$line"

    /** A row of the scope dropdown of Find in Files: its choice (`.` for the whole project root). */
    fun findInFilesScopeRow(scope: String) = "findInFiles.scope:$scope"

    fun gotoSymbolItem(name: String, relativePath: String) = "gotoSymbol.item:$name:$relativePath"

    fun structureItem(name: String, line: Int) = "structure.item:$name:$line"
}
