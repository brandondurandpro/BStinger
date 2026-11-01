package org.example.kotlineditor.ui

/**
 * Semantics test tags of the git module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
@Suppress("TooManyFunctions")
object GitTags {
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
}
