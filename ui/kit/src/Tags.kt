package org.example.kotlineditor.ui

/**
 * Semantics test tags used by several UI modules, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
object Tags {
    const val MENU_CLOSE_TAB = "menu.closeTab"
    const val MENU_CLOSE_OTHER_TABS = "menu.closeOtherTabs"
    const val MENU_CLOSE_TABS_LEFT = "menu.closeTabsLeft"
    const val MENU_CLOSE_TABS_RIGHT = "menu.closeTabsRight"
    const val MENU_CLOSE_ALL_TABS = "menu.closeAllTabs"

    fun toolHeader(window: String) = "tool.header:$window"
}
