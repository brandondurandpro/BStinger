package org.example.kotlineditor.ui

/**
 * Semantics test tags of the terminal module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
object TerminalTags {
    const val TERMINAL = "terminal"
    const val TERMINAL_LOST = "terminal.lost"
    const val TERMINAL_INPUT = "terminal.input"
    const val TERMINAL_NEW = "terminal.new"
    const val TERMINAL_TABS = "terminal.tabs"
    const val TERMINAL_TAB_MENU = "terminal.tab.menu"
    const val TERMINAL_HIDE = "terminal.hide"

    fun terminalTab(number: Int) = "terminal.tab:$number"

    fun terminalTabName(number: Int) = "terminal.tab.name:$number"

    fun terminalTabTooltip(number: Int) = "terminal.tab.tooltip:$number"

    fun terminalTabClose(number: Int) = "terminal.tab.close:$number"

    fun terminalLine(index: Int) = "terminal.line:$index"
}
