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
    const val MENU_COPY_PATH = "menu.copyPath"
    const val MENU_RELOAD = "menu.reload"
    const val GOTO_FILE = "gotoFile"
    const val RECENT_FILES = "recentFiles"
    const val GOTO_FILE_INPUT = "gotoFile.input"
    const val FIND_IN_FILES = "findInFiles"
    const val FIND_IN_FILES_INPUT = "findInFiles.input"
    const val FIND_IN_FILES_COUNT = "findInFiles.count"
    const val GOTO_SYMBOL = "gotoSymbol"
    const val GOTO_SYMBOL_INPUT = "gotoSymbol.input"
    const val STRUCTURE = "structure"
    const val STRUCTURE_INPUT = "structure.input"

    fun treeRow(relativePath: String) = "tree:$relativePath"

    fun treeSticky(relativePath: String) = "tree.sticky:$relativePath"

    fun treeChevron(relativePath: String) = "tree.chevron:$relativePath"

    fun treeGitStatus(relativePath: String, status: String) = "tree.git:$relativePath:$status"

    fun gotoFileItem(relativePath: String) = "gotoFile.item:$relativePath"

    fun findInFilesItem(relativePath: String, line: Int) = "findInFiles.item:$relativePath:$line"

    fun gotoSymbolItem(name: String, relativePath: String) = "gotoSymbol.item:$name:$relativePath"

    fun structureItem(name: String, line: Int) = "structure.item:$name:$line"
}
