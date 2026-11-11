package org.example.kotlineditor.ui

/** Semantics test tags of the Run tool window, shared with the end-to-end tests. */
@Suppress("TooManyFunctions")
object RunTags {
    const val RUN = "run"
    const val TOOL_BUTTON = "tool.run"
    const val SELECTOR = "run.selector"
    const val SELECTOR_MENU = "run.selector.menu"
    const val START = "run.start"
    const val STOP = "run.stop"
    const val HIDE = "run.hide"
    const val STATUS = "run.status"
    const val NEW = "run.new"
    const val ADD_FIRST = "run.addFirst"
    const val EDIT = "run.edit"
    const val EMPTY = "run.empty"
    const val TAB_OUTPUT = "run.tab.output"
    const val TAB_TESTS = "run.tab.tests"
    const val OUTPUT = "run.output"
    const val DROPPED = "run.output.dropped"
    const val TESTS = "run.tests"
    const val SUMMARY = "run.tests.summary"
    const val ONLY_FAILED = "run.tests.onlyFailed"
    const val NO_TESTS = "run.tests.none"
    const val DETAILS = "run.tests.details"
    const val EDITOR = "run.editor"
    const val EDITOR_NAME = "run.editor.name"
    const val EDITOR_COMMAND = "run.editor.command"
    const val EDITOR_DIRECTORY = "run.editor.directory"
    const val EDITOR_KIND_RUN = "run.editor.kind.run"
    const val EDITOR_KIND_TESTS = "run.editor.kind.tests"
    const val EDITOR_SAVE = "run.editor.save"
    const val EDITOR_DELETE = "run.editor.delete"
    const val EDITOR_CANCEL = "run.editor.cancel"

    const val WIDGET = "run.widget"
    const val WIDGET_SELECTOR = "run.widget.selector"
    const val WIDGET_MENU = "run.widget.menu"
    const val WIDGET_START = "run.widget.start"
    const val WIDGET_STOP = "run.widget.stop"
    const val WIDGET_NEW = "run.widget.new"
    const val WIDGET_EDIT = "run.widget.edit"
    const val WIDGET_SHOW = "run.widget.show"
    const val WIDGET_SEARCH = "run.widget.search"
    const val WIDGET_STATUS = "run.widget.status"
    const val WIDGET_NO_MATCH = "run.widget.noMatch"

    fun widgetFolder(folder: String) = "run.widget.folder:$folder"

    fun widgetRecentFolder(folder: String) = "run.widget.recentFolder:$folder"

    fun widgetRecent(id: String) = "run.widget.recent:$id"

    fun widgetRunning(id: String) = "run.widget.running:$id"

    fun session(id: String) = "run.session:$id"

    fun sessionClose(id: String) = "run.session.close:$id"

    fun widgetItem(id: String) = "run.widget.item:$id"

    fun configuration(id: String) = "run.configuration:$id"

    fun line(index: Int) = "run.line:$index"

    fun link(line: Int, number: Int) = "run.link:$line:$number"

    fun suite(name: String) = "run.suite:$name"

    fun test(suite: String, name: String) = "run.test:$suite:$name"

    fun detailLine(index: Int) = "run.detail:$index"
}
