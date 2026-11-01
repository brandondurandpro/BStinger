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
    const val PROJECT_AGENT_MODE = "project.agentMode"
    const val PROJECT_OPEN_OTHER = "project.openOther"
    const val PROJECT_MENU = "project.menu"
    const val GIT_BRANCH = "git.branch"
    const val BRANCH_PICKER = "branch.picker"
    const val BRANCH_SEARCH = "branch.search"
    const val BRANCH_REFRESH = "branch.refresh"
    const val BRANCH_EMPTY = "branch.empty"
    const val BRANCH_NO_MATCH = "branch.noMatch"
    const val BRANCH_CHECKING_OUT = "branch.checkingOut"
    const val BRANCH_ERROR = "branch.error"
    const val BRANCH_SELECTION_COUNT = "branch.selectionCount"
    const val BRANCH_BULK_BAR = "branch.bulkBar"
    const val BRANCH_PICK_COUNT = "branch.pickCount"
    const val BRANCH_SELECT_ALL = "branch.selectAll"
    const val BRANCH_CLEAR_PICKS = "branch.clearPicks"
    const val BRANCH_PIN_PICKED = "branch.pinPicked"
    const val BRANCH_UNPIN_PICKED = "branch.unpinPicked"
    const val BRANCH_DELETE_PICKED = "branch.deletePicked"
    const val BRANCH_CLEANUP = "branch.cleanup"
    const val BRANCH_DELETE_DIALOG = "branch.deleteDialog"
    const val BRANCH_DELETE_SUMMARY = "branch.deleteSummary"
    const val BRANCH_DELETE_CONFIRM = "branch.deleteConfirm"
    const val BRANCH_DELETE_CANCEL = "branch.deleteCancel"
    const val BRANCH_DELETING = "branch.deleting"
    const val BRANCH_DONE = "branch.done"
    const val BRANCH_SYNC_STATUS = "branch.syncStatus"
    const val BRANCH_SYNC_DISMISS = "branch.syncDismiss"
    const val PROJECT_PANEL = "project.panel"
    const val GIT_PANEL = "git.panel"
    const val GIT_EMPTY = "git.empty"
    const val GIT_NOT_A_REPOSITORY = "git.notRepository"
    const val GIT_REFRESH = "git.refresh"
    const val GIT_HIDE = "git.hide"
    const val GIT_BLAME = "git.blame"
    const val GIT_SELECT_ALL = "git.selectAll"
    const val GIT_COMMIT_MESSAGE = "git.commitMessage"
    const val GIT_COMMIT = "git.commit"
    const val GIT_BRANCH_BUTTON = "git.branchButton"
    const val GIT_PULL = "git.pull"
    const val GIT_PUSH = "git.push"
    const val GIT_MESSAGE = "git.message"
    const val TOOL_PROJECT = "tool.project"
    const val TOOL_GIT = "tool.git"
    const val TOOL_BRANCHES = "tool.branches"
    const val BRANCHES_PANEL = "branches.panel"
    const val HISTORY_REFRESH = "history.refresh"
    const val HISTORY_HIDE = "history.hide"
    const val HISTORY_BRANCH_SEARCH = "history.branchSearch"
    const val HISTORY_BRANCH_LIST = "history.branchList"
    const val HISTORY_TITLE = "history.title"
    const val HISTORY_LOADING = "history.loading"
    const val HISTORY_ERROR = "history.error"
    const val HISTORY_EMPTY = "history.empty"
    const val HISTORY_END = "history.end"
    const val HISTORY_DETAILS = "history.details"
    const val HISTORY_SEARCH = "history.search"
    const val HISTORY_SEARCH_CLEAR = "history.searchClear"
    const val HISTORY_NO_MATCH = "history.noMatch"
    const val HISTORY_BRANCHES_SPLITTER = "history.branchesSplitter"
    const val HISTORY_DETAILS_SPLITTER = "history.detailsSplitter"
    const val TOOL_TERMINAL = "tool.terminal"
    const val TOOL_NETWORK = "tool.network"
    const val NETWORK = "network"
    const val NETWORK_RECORD = "network.record"
    const val NETWORK_CLEAR = "network.clear"
    const val NETWORK_PINGS = "network.pings"
    const val NETWORK_HIDE = "network.hide"
    const val NETWORK_FILTER = "network.filter"
    const val NETWORK_TOTALS = "network.totals"
    const val NETWORK_EMPTY = "network.empty"
    const val NETWORK_UNAVAILABLE = "network.unavailable"
    const val NETWORK_DETAIL = "network.detail"
    const val WORKSPACE = "workspace"
    const val MODE_SWITCH = "mode.switch"
    const val MODE_IDE = "mode.ide"
    const val MODE_AGENT = "mode.agent"
    const val AGENT = "agent"
    const val AGENT_LIST = "agent.list"
    const val AGENT_NEW = "agent.new"
    const val AGENT_FILTER = "agent.filter"
    const val AGENT_DELETE = "agent.delete"
    const val AGENT_TABS = "agent.tabs"
    const val AGENT_EMPTY = "agent.empty"
    const val AGENT_PROMPT = "agent.prompt"
    const val AGENT_SEND = "agent.send"
    const val AGENT_STOP = "agent.stop"
    const val AGENT_STRIP_CONVERSATIONS = "agent.strip.conversations"
    const val AGENT_DETAILS = "agent.details"
    const val AGENT_DETAIL_CONVERSATION = "agent.detail.conversation"
    const val AGENT_DETAIL_EDITOR = "agent.detail.editor"
    const val AGENT_WORKSPACE_STATUS = "agent.workspaceStatus"
    const val AGENT_MODE_DIRECT = "agent.mode.direct"
    const val AGENT_MODE_WORKTREE = "agent.mode.worktree"
    const val DIFF = "diff"
    const val DIFF_TOOLBAR = "diff.toolbar"
    const val DIFF_OPEN_FILE = "diff.openFile"
    const val DIFF_LAYOUT_TOGGLE = "diff.layoutToggle"
    const val DIFF_SPLIT = "diff.split"
    const val TERMINAL = "terminal"
    const val TERMINAL_LOST = "terminal.lost"
    const val TERMINAL_INPUT = "terminal.input"
    const val TERMINAL_NEW = "terminal.new"
    const val TERMINAL_TABS = "terminal.tabs"
    const val TERMINAL_TAB_MENU = "terminal.tab.menu"
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
    const val TAB_MENU = "tab.menu"
    const val MENU_CLOSE_TAB = "menu.closeTab"
    const val MENU_CLOSE_OTHER_TABS = "menu.closeOtherTabs"
    const val MENU_CLOSE_TABS_LEFT = "menu.closeTabsLeft"
    const val MENU_CLOSE_TABS_RIGHT = "menu.closeTabsRight"
    const val MENU_CLOSE_ALL_TABS = "menu.closeAllTabs"
    const val MENU_SPLIT_RIGHT = "menu.splitRight"
    const val MENU_UNSPLIT = "menu.unsplit"
    const val MENU_COPY_TAB_PATH = "menu.copyTabPath"
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
    const val STATUS_OPENING = "status.opening"
    const val STATUS_ERROR = "status.error"
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

    fun treeSticky(relativePath: String) = "tree.sticky:$relativePath"

    fun treeChevron(relativePath: String) = "tree.chevron:$relativePath"

    fun treeGitStatus(relativePath: String, status: String) = "tree.git:$relativePath:$status"

    fun pane(index: Int) = "editor.pane:$index"

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

    fun toolHeader(window: String) = "tool.header:$window"

    fun terminalTab(number: Int) = "terminal.tab:$number"

    fun terminalTabName(number: Int) = "terminal.tab.name:$number"

    fun terminalTabTooltip(number: Int) = "terminal.tab.tooltip:$number"

    fun terminalTabClose(number: Int) = "terminal.tab.close:$number"

    fun dockSplitter(dock: String) = "dock.splitter:$dock"

    fun line(index: Int) = "line:$index"

    /** The text of a line of the editor, as wide as the text (without the gutter). */
    fun lineText(index: Int) = "line.text:$index"

    fun foldMarker(line: Int) = "fold:$line"

    fun agentConversation(id: Int) = "agent.conversation.$id"

    fun agentTab(id: Int) = "agent.tab.$id"

    fun agentTabClose(id: Int) = "agent.tab.$id.close"

    fun agentTabDetails(id: Int) = "agent.tab.$id.details"

    fun agentView(id: Int) = "agent.view.$id"

    fun agentMessage(index: Int) = "agent.message.$index"
}

/** Test tags of the items of lists and popups (recent projects, search results, terminal lines). */
@Suppress("TooManyFunctions")
object ItemTags {
    fun recentProject(path: String) = "recent:$path"

    fun recentRemove(path: String) = "recent.remove:$path"

    /** A recent project in the menu of the toolbar's project name, which switches to it. */
    fun switchProject(path: String) = "project.switch:$path"

    fun terminalLine(index: Int) = "terminal.line:$index"

    fun gitChange(relativePath: String) = "git.change:$relativePath"

    fun gitChangeCheck(relativePath: String) = "git.change.check:$relativePath"

    fun gitBranch(name: String) = "git.branchItem:$name"

    fun branchItem(name: String) = "branch.item:$name"

    fun branchSelect(name: String) = "branch.select:$name"

    fun branchSync(name: String) = "branch.sync:$name"

    fun branchAhead(name: String) = "branch.ahead:$name"

    fun branchBehind(name: String) = "branch.behind:$name"

    fun branchPin(name: String) = "branch.pin:$name"

    fun branchToDelete(name: String) = "branch.toDelete:$name"

    fun branchToDeleteCheck(name: String) = "branch.toDeleteCheck:$name"

    fun historyBranch(name: String) = "history.branch:$name"

    fun historySection(name: String) = "history.section:$name"

    fun historyCommit(hash: String) = "history.commit:$hash"

    fun historyFile(path: String) = "history.file:$path"

    fun branchMenuHistory(name: String) = "branch.menu.history:$name"

    fun pinnedBranchMenu(name: String) = "branch.pinnedMenu:$name"

    fun branchMenuCheckout(name: String) = "branch.menu.checkout:$name"

    fun branchMenuPull(name: String) = "branch.menu.pull:$name"

    fun branchMenuPush(name: String) = "branch.menu.push:$name"

    fun branchMenuChanges(name: String) = "branch.menu.changes:$name"

    fun branchMenuUnpin(name: String) = "branch.menu.unpin:$name"

    fun pinnedBranch(name: String) = "branch.pinned:$name"

    fun unpinnedBranch(name: String) = "branch.unpinned:$name"

    fun networkRecord(seq: Long) = "network.record:$seq"

    fun gotoFileItem(relativePath: String) = "gotoFile.item:$relativePath"

    fun findInFilesItem(relativePath: String, line: Int) = "findInFiles.item:$relativePath:$line"

    fun gotoSymbolItem(name: String, relativePath: String) = "gotoSymbol.item:$name:$relativePath"

    fun structureItem(name: String, line: Int) = "structure.item:$name:$line"
}
