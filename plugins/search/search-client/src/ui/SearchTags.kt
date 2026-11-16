package org.example.kotlineditor.ui

/** Semantics test tags of the Find in Files panel, shared with the end-to-end tests. */
@Suppress("TooManyFunctions")
object SearchTags {
    const val FIND_IN_FILES = "findInFiles"
    const val TOOL_BUTTON = "tool.search"
    const val PANEL = "searchPanel"
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
    const val FIND_IN_FILES_SCOPE_FILTER = "findInFiles.scopeFilter"
    const val FIND_IN_FILES_GLOB_CLEAR = "findInFiles.globClear"
    const val FIND_IN_FILES_CLEAR = "findInFiles.clear"
    const val FIND_IN_FILES_SCOPE_CLEAR = "findInFiles.scopeClear"
    const val FIND_IN_FILES_SCOPE_FILTER_CLEAR = "findInFiles.scopeFilterClear"
    const val FIND_IN_FILES_SCOPE_ALL = "findInFiles.scopeAll"
    const val FIND_IN_FILES_SCOPE_NONE = "findInFiles.scopeNone"
    const val FIND_IN_FILES_ERROR = "findInFiles.error"
    fun findInFilesFile(relativePath: String) = "findInFiles.file:$relativePath"
    fun findInFilesItem(relativePath: String, line: Int) = "findInFiles.item:$relativePath:$line"
    fun findInFilesGlobSuggestion(glob: String) = "findInFiles.glob:$glob"
    fun findInFilesScopeRow(scope: String) = "findInFiles.scope:$scope"
}
