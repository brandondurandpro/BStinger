package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitFileStatus
import java.io.IOException

private const val MAX_MESSAGE_LENGTH = 300

/**
 * Git in a project: the branch, the status of the files, the commit and the branches of the toolbar, and the Git
 * actions (commit, checkout, pull, push...). [synchronize] reloads the project's files after an action that changed
 * them (a checkout, a pull).
 */
@Stable
@Suppress("TooManyFunctions")
class GitState(
    private val root: FileEntry,
    private val git: GitClient,
    private val scope: CoroutineScope,
    private val editor: EditorState,
    private val synchronize: () -> Unit,
) {
    /** Message of the last Git action (commit, branch switch, pull, push), shown in the Git panel. */
    var message: String? by mutableStateOf(null)

    /** True when [message] reports a failure. */
    var messageIsError by mutableStateOf(false)

    /** True while a Git action is running, so its buttons do not start another one. */
    var busy by mutableStateOf(false)

    /** Text of the commit message field of the Git panel. */
    var commitMessage by mutableStateOf("")

    /** Local branches of the repository, for the branch menu of the Git panel. */
    var branches: List<String> by mutableStateOf(emptyList())
        private set

    /** Local branches with their remote synchronization, for the branch picker of the toolbar. */
    var branchInfos: List<GitBranchInfo> by mutableStateOf(emptyList())
        private set

    /** Branches the user pinned in the toolbar (one or several); clicking a branch name still checks it out. */
    var selectedBranches: Set<String> by mutableStateOf(emptySet())
        private set

    /**
     * Branches ticked in the picker for a bulk action (pin, delete), as opposed to [selectedBranches], which stay
     * pinned in the toolbar. Kept while the query changes, and pruned when a branch disappears.
     */
    var pickedBranches: Set<String> by mutableStateOf(emptySet())
        private set

    /** The last pull or push of a pinned branch, shown next to the toolbar chips; null once dismissed. */
    var branchSync: BranchSync? by mutableStateOf(null)

    /** Whether the toolbar's branch picker is open. */
    var branchPickerOpen by mutableStateOf(false)

    /** Current Git branch (or short commit when detached), shown in the toolbar like IntelliJ's VCS widget. */
    var currentBranch: String? by mutableStateOf(null)
        private set

    /** Git status of changed files, keyed by project-relative path (colors the tree, lists the Git panel). */
    var status: Map<String, GitFileStatus> by mutableStateOf(emptyMap())
        private set

    /** The paths checked in the Git panel for the next commit; the Commit button commits only these. */
    var selectedForCommit: Set<String> by mutableStateOf(emptySet())
        private set

    /** False when the project is not a Git repository (or Git is unavailable). */
    var isRepository by mutableStateOf(true)
        private set

    /** The Branches tool window: branches and the history of the selected one. */
    val history = BranchHistoryState(root.path, git, scope)

    private var statusJob: Job? = null

    // A refresh asked to reload every editor; kept when a later refresh (a save) cancels it.
    private var editorsReloadPending = false

    /** Checks or unchecks [path] (the checkbox of a change in the Git panel) for the next commit. */
    fun toggleCommitSelection(path: String) {
        selectedForCommit = if (path in selectedForCommit) selectedForCommit - path else selectedForCommit + path
    }

    /** Ticks every change of the status for the next commit (the master checkbox), or unticks them all. */
    fun toggleAllCommitSelection() {
        selectedForCommit = if (selectedForCommit.size == status.size) emptySet() else status.keys
    }

    /** Reloads the Git branch and the status of every file. */
    fun refresh() {
        refreshBranches()
        refreshStatus()
    }

    /**
     * Reloads the branches and their distance from their remote-tracking branch. A failed call (server unreachable)
     * keeps the last list, so the branch picker still shows what it knows while the link comes back.
     */
    fun refreshBranches() {
        scope.launch {
            runCatching { git.branch(root.path) }.onSuccess {
                currentBranch = it
                isRepository = it != null
            }
            if (isRepository) {
                runCatching { git.branchDetails(root.path) }
                    .onSuccess(::applyBranches)
                    .onFailure {
                        // An older server knows the names but not their upstream: show the names at least.
                        runCatching { git.branches(root.path) }
                            .onSuccess { names -> applyBranches(names.map(::branchInfoOf)) }
                    }
            }
        }
    }

    private fun applyBranches(infos: List<GitBranchInfo>) {
        branchInfos = sortedBranches(infos)
        branches = infos.map { it.name }
        val names = infos.mapTo(HashSet()) { it.name }
        selectedBranches = selectedBranches intersect names
        pickedBranches = pickedBranches intersect names
    }

    /** Pins or unpins [name] in the toolbar. */
    fun toggleBranchSelection(name: String) {
        selectedBranches =
            if (name in selectedBranches) selectedBranches - name else selectedBranches + name
    }

    /** Ticks or unticks [name] for a bulk action (the checkbox of the branch picker). */
    fun toggleBranchPick(name: String) {
        pickedBranches = if (name in pickedBranches) pickedBranches - name else pickedBranches + name
    }

    fun clearBranchPicks() {
        pickedBranches = emptySet()
    }

    /** Ticks every branch of [names] (the picker's Select all, which ticks the branches the query shows). */
    fun pickBranches(names: Collection<String>) {
        pickedBranches = pickedBranches + names
    }

    // Branches just deleted: they leave the toolbar and the picks at once, before the refresh lists the others.
    internal fun forgetBranches(names: Set<String>) {
        selectedBranches = selectedBranches - names
        pickedBranches = pickedBranches - names
        branchInfos = branchInfos.filter { it.name !in names }
        branches = branches.filter { it !in names }
    }

    /** Pins every ticked branch in the toolbar. */
    fun pinPickedBranches() {
        selectedBranches = selectedBranches + pickedBranches
    }

    /** Removes every ticked branch from the toolbar chips. */
    fun unpinPickedBranches() {
        selectedBranches = selectedBranches - pickedBranches
    }

    /** Local branches whose remote is missing or gone, other than the current one: the cleanup shortcut's target. */
    fun branchesWithoutRemote(): List<GitBranchInfo> = branchInfos.filter { !it.current && !it.hasUpstream }

    private fun branchInfoOf(name: String) =
        GitBranchInfo(name = name, current = name == currentBranch, upstream = null, ahead = 0, behind = 0)

    // Current branch first, then by name: the branch picker and the toolbar chips keep a stable, familiar order.
    private fun sortedBranches(infos: List<GitBranchInfo>) =
        infos.sortedWith(compareByDescending<GitBranchInfo> { it.current }.thenBy { it.name })

    /**
     * Reloads the status of every file and, when [reloadEditors], the diffs, gutter marks and blame of the open
     * editors (HEAD may have moved). A save passes false: it reloads only the saved file (`EditorState.reloadGitOf`).
     */
    fun refreshStatus(reloadEditors: Boolean = true) {
        editorsReloadPending = editorsReloadPending || reloadEditors
        statusJob?.cancel()
        statusJob =
            scope.launch {
                runCatching { git.status(root.path) }.onSuccess {
                    status = it
                    // Files that left the status are unchecked, so the selection never points outside the list.
                    selectedForCommit = selectedForCommit intersect it.keys
                    if (editorsReloadPending) {
                        editorsReloadPending = false
                        editor.reloadDiffs()
                        editor.reloadChanges()
                        editor.reloadBlames()
                    }
                }
            }
    }

    /**
     * Commits the changes the user checked in the Git panel ([selectedForCommit]) with the message [text]; the other
     * changes stay behind. The panel keeps the selection while the status changes, so a path that left it is simply not
     * committed.
     */
    fun commit(text: String) {
        val paths = selectedForCommit
        if (text.isBlank()) {
            message = "A commit message is required"
            messageIsError = true
            return
        }
        // The Commit button is disabled then; the guard keeps another caller (a shortcut) from committing everything.
        if (paths.isEmpty()) return
        scope.launch {
            gitOperation {
                git.commit(root.path, text.trim(), paths.toList())
                commitMessage = ""
                val noun = if (paths.size == 1) "file" else "files"
                "Committed ${paths.size} $noun"
            }
        }
    }

    /** Checks out a local branch (the branch menu of the Git panel). */
    fun checkoutBranch(branch: String, onDone: (Boolean) -> Unit = {}) {
        if (branch == currentBranch) {
            onDone(true)
            return
        }
        scope.launch { onDone(checkout(branch)) }
    }

    /**
     * Checks out [branch] and returns whether it worked; a failure stays in [message] so the caller can show it (the
     * branch picker keeps its panel open and explains why, instead of closing as if the checkout had happened).
     */
    suspend fun checkout(branch: String): Boolean {
        if (branch == currentBranch) return true
        return gitOperation(reloadFiles = true) {
            git.checkout(root.path, branch)
            "Switched to $branch"
        }
    }

    /**
     * Deletes the local branches the user confirmed, even when they are not merged. A failure leaves [message] with
     * Git's reason and keeps every pin and pick; on success the deleted branches leave both the toolbar and the picker.
     */
    fun deleteBranches(branches: Collection<String>, onDone: (Boolean) -> Unit = {}) {
        val names = branches.toSet()
        if (names.isEmpty()) {
            onDone(true)
            return
        }
        scope.launch {
            val deleted =
                gitOperation {
                    git.deleteBranches(root.path, names.toList())
                    val noun = if (names.size == 1) "branch" else "branches"
                    "${names.size} $noun deleted"
                }
            // A partial failure still deleted the other branches: reload the list so that it shows only what is left.
            if (deleted) forgetBranches(names) else refreshBranches()
            onDone(deleted)
        }
    }

    /**
     * Pulls (fast-forward) or pushes a pinned branch without checking it out, from its chip in the toolbar. The current
     * branch is pulled for real, and its open files reloaded. [branchSync] follows the operation.
     */
    fun syncBranch(branch: String, action: BranchSync.Action) {
        if (busy) return
        branchSync = BranchSync(branch, action, running = true)
        scope.launch {
            val current = branch == currentBranch
            val done =
                gitOperation(reloadFiles = current && action == BranchSync.Action.Pull) {
                    when (action) {
                        BranchSync.Action.Pull -> git.pullBranch(root.path, branch)
                        BranchSync.Action.Push -> git.pushBranch(root.path, branch)
                    }.ifBlank { "${action.done} $branch" }
                }
            branchSync =
                if (done) {
                    BranchSync(branch, action, running = false)
                } else {
                    val reason = message ?: "Git command failed"
                    BranchSync(branch, action, running = false, message = reason, isError = true)
                }
        }
    }

    /** Pulls the current branch (Git panel's Pull button). */
    fun pull() =
        scope.launch {
            gitOperation(reloadFiles = true) {
                git.pull(root.path).ifBlank { "Pulled" }
            }
        }

    /** Pushes the current branch (Git panel's Push button). */
    fun push() =
        scope.launch {
            gitOperation {
                git.push(root.path).ifBlank { "Pushed" }
            }
        }

    /** Shows or hides the blame annotations of the active editor (Git panel's Annotate button). */
    fun toggleBlame() = editor.toggleBlame(editor.activeTab)

    // Runs a Git action once at a time: saves the editors first, then reports Git's output (or its error) in the panel.
    // Returns whether the action ran and succeeded, for callers that wait for it.
    private suspend fun gitOperation(reloadFiles: Boolean = false, block: suspend () -> String): Boolean {
        if (busy) return false
        busy = true
        message = null
        messageIsError = false
        return try {
            editor.flush()
            val output = block().trim().replace('\n', ' ').take(MAX_MESSAGE_LENGTH)
            message = output.ifBlank { "Done" }
            if (reloadFiles) synchronize() else refresh()
            true
        } catch (e: IOException) {
            message = e.message ?: "Git command failed"
            messageIsError = true
            refreshStatus()
            false
        } finally {
            busy = false
        }
    }
}

/** A pull or push of one branch from the toolbar: running, then its result. */
data class BranchSync(
    val branch: String,
    val action: Action,
    val running: Boolean,
    val message: String? = null,
    val isError: Boolean = false,
) {
    enum class Action(val running: String, val done: String) {
        Pull("Pulling", "Pulled"),
        Push("Pushing", "Pushed"),
    }

    /** What the toolbar says: the action in progress, or its result. */
    val text: String
        get() =
            when {
                running -> "${action.running} $branch…"
                isError -> "Cannot ${action.name.lowercase()} $branch: $message"
                else -> "${action.done} $branch"
            }
}
