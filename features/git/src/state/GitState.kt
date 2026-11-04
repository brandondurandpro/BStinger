package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.telemetry.ClientTelemetry
import org.example.kotlineditor.telemetry.Operation
import org.example.kotlineditor.telemetry.measureToFrame
import org.example.kotlineditor.telemetry.outcome
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

private const val MAX_MESSAGE_LENGTH = 300

// How long after the refresh of an IDE Git action read the branches the watch of the disk may still report what the
// action wrote in `.git` (it settles changes for 100 ms, then the client polls): a change of `.git` reported until then
// reloads the branches only once that time is past.
private const val OWN_WRITES_MILLIS = 1_000L
private const val NANOS_PER_MILLI = 1_000_000L

/** Time between two background fetches of the remotes, while the project is open. */
const val AUTO_FETCH_MILLIS = 5 * 60 * 1000L

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
    pinned: Set<String> = emptySet(),
) {
    /** Message of the last Git action (commit, branch switch, pull, push), shown in the Git panel. */
    var message: String? by mutableStateOf(null)

    /** True when [message] reports a failure. */
    var messageIsError by mutableStateOf(false)

    /** True while a Git action is running, so its buttons do not start another one. */
    var busy by mutableStateOf(false)

    /**
     * True while a background fetch runs: a pull or a push asked meanwhile waits for it (they would fail on the lock
     * of the refs it updates), as in VS Code and IntelliJ, which run one Git command on the remotes at a time.
     */
    var fetching by mutableStateOf(false)
        private set

    /** Text of the commit message field of the Git panel. */
    var commitMessage by mutableStateOf("")

    /** Local branches of the repository, for the branch menu of the Git panel. */
    var branches: List<String> by mutableStateOf(emptyList())
        private set

    /** Local branches with their remote synchronization, for the branch picker of the toolbar. */
    var branchInfos: List<GitBranchInfo> by mutableStateOf(emptyList())
        private set

    /**
     * Branches the user pinned in the toolbar (one or several); clicking a branch name still checks it out. Saved with
     * the preferences of the project ([pinnedBranchesPreference]), so they come back when it is opened again.
     */
    var selectedBranches: Set<String> by mutableStateOf(pinned)
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

    // A pull or push waits for a background fetch to end: two Git processes updating the same refs fail on their lock.
    private val remoteLock = Mutex()

    // A refresh asked to reload every editor; kept when a later refresh (a save) cancels it.
    private var editorsReloadPending = false

    // One load of the branches at a time, and the loads asked meanwhile make one more: with thousands of branches, two
    // loads in parallel (a push refreshing, the watch of the disk seeing the refs it wrote) each take twice as long.
    private val branchLoads = Mutex()
    private val branchLoadsAsked = AtomicLong()
    private val branchLoadsCovered = AtomicLong()

    // The load of the branches an IDE Git action (a fetch too) asked after it, which reads all it wrote in `.git`; the
    // watch of the disk reports those writes a little later (it settles them first), often while that load still runs.
    @Volatile
    private var actionLoad: Long? = null
    private val branchLoadsDone = AtomicLong()

    @Volatile
    private var branchLoadDoneAt = 0L
    private var laterBranchReload: Job? = null

    // `.git` changed while an IDE Git action ran: the action's own refresh reloads the branches, after it, but a failed
    // action refreshes only the status.
    @Volatile
    private var branchesChangedDuringAction = false

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
        // Asked now, not once the coroutine runs: a reload of the watch of the disk asked meanwhile waits behind it.
        val ticket = branchLoadsAsked.incrementAndGet()
        scope.launch { loadBranches(ticket) }
    }

    /**
     * Reloads what a change on disk may have moved ([branchesToo]: something changed in `.git`) and returns once it is
     * shown: the watch of the disk waits for it before asking for more changes, so that a burst of them (thousands of
     * branches deleted, a ref each) costs a reload or two, not one per change.
     */
    suspend fun reloadAfterDiskChange(branchesToo: Boolean) {
        // An IDE Git action runs: its own refresh, after it, reads everything it wrote.
        if (busy) {
            branchesChangedDuringAction = branchesChangedDuringAction || branchesToo
            return
        }
        // Most likely what an action just wrote in `.git`, reported after its refresh started: reading every branch
        // again now would make the next action (a pull right after the fetch) wait for it. Read once that is past,
        // since another program may have moved a branch too; the status, cheap, is read now anyway.
        val ownWritesLikely = millisLeftOfOwnWrites() > 0
        if (branchesToo && ownWritesLikely) reloadBranchesLater()
        val branchesMoved = branchesToo && !ownWritesLikely
        if (branchesMoved) loadBranches()
        editorsReloadPending = editorsReloadPending || branchesMoved
        statusJob?.cancel()
        ClientTelemetry.measureToFrame(Operation.GitStatus, outcomeOf = { read: Result<*> -> read.outcome() }) {
            applyStatus()
        }
    }

    private fun millisLeftOfOwnWrites(): Long {
        val ticket = actionLoad ?: return 0
        val stillReading = branchLoadsDone.get() < ticket
        val elapsedMillis = (System.nanoTime() - branchLoadDoneAt) / NANOS_PER_MILLI
        return if (stillReading) OWN_WRITES_MILLIS else OWN_WRITES_MILLIS - elapsedMillis
    }

    // Reloads the branches, the status and the editors once no IDE Git action may still be writing; an action that runs
    // by then reloads them itself.
    private fun reloadBranchesLater() {
        if (laterBranchReload?.isActive == true) return
        laterBranchReload =
            scope.launch {
                var leftMillis = millisLeftOfOwnWrites()
                while (leftMillis > 0) {
                    // Wall-clock time, also under a test's virtual clock, like the fetches.
                    withContext(Dispatchers.Default) { delay(leftMillis) }
                    leftMillis = millisLeftOfOwnWrites()
                }
                if (busy) {
                    branchesChangedDuringAction = true
                } else {
                    loadBranches()
                    refreshStatus()
                }
            }
    }

    // The refresh of the branches after an IDE Git action that may have written in `.git`.
    private fun refreshBranchesAfterAction() {
        refreshBranches()
        actionLoad = branchLoadsAsked.get()
    }

    private suspend fun loadBranches(ticket: Long = branchLoadsAsked.incrementAndGet()) {
        branchLoads.withLock {
            // A load that started after this one was asked has read what it would read.
            if (branchLoadsCovered.get() >= ticket) return
            val covered = branchLoadsAsked.get()
            branchLoadsCovered.set(covered)
            readBranches()
            branchLoadDoneAt = System.nanoTime()
            branchLoadsDone.set(covered)
        }
    }

    private suspend fun readBranches() {
        ClientTelemetry.measureToFrame(
            Operation.GitBranch,
            outcomeOf = { read: Result<String?> -> read.outcome() },
        ) {
            runCatching { git.branch(root.path) }.onSuccess {
                currentBranch = it
                isRepository = it != null
            }
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

    private fun applyBranches(infos: List<GitBranchInfo>) {
        branchInfos = sortedBranches(infos)
        branches = infos.map { it.name }
        val names = infos.mapTo(HashSet()) { it.name }
        // A repository has a branch once it has a commit: an empty list is a failed read, which must not lose the pins.
        if (names.isNotEmpty()) selectedBranches = selectedBranches intersect names
        pickedBranches = pickedBranches intersect names
    }

    /** The saved form of [selectedBranches]: one branch name per line (Git forbids line breaks in them). */
    fun pinnedBranchesPreference(): Pair<String, String> =
        pinnedBranchesKey(root.path) to selectedBranches.joinToString("\n")

    /**
     * Fetches the remotes now, then every [AUTO_FETCH_MILLIS] until the project is closed (its scope is cancelled), and
     * reloads the branches after each, so that the toolbar counts the commits to pull against what the remotes have
     * now. Silent: a failure (offline, no remote) waits for the next fetch.
     */
    fun startAutoFetch(): Job =
        scope.launch {
            while (true) {
                fetchRemotes()
                // Wall-clock time, also under a test's virtual clock, which would otherwise fetch without a pause.
                withContext(Dispatchers.Default) { delay(AUTO_FETCH_MILLIS) }
            }
        }

    private suspend fun fetchRemotes() {
        fetching = true
        val fetched =
            try {
                remoteLock.withLock { runCatching { git.fetch(root.path) } }
            } finally {
                fetching = false
            }
        if (fetched.isSuccess) refreshBranchesAfterAction()
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
                ClientTelemetry.measureToFrame(Operation.GitStatus, outcomeOf = { read: Result<*> -> read.outcome() }) {
                    applyStatus()
                }
            }
    }

    private suspend fun applyStatus(): Result<*> =
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
                    val output =
                        remoteLock.withLock {
                            when (action) {
                                BranchSync.Action.Pull -> git.pullBranch(root.path, branch)
                                BranchSync.Action.Push -> git.pushBranch(root.path, branch)
                            }
                        }
                    output.ifBlank { "${action.done} $branch" }
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
                val output = remoteLock.withLock { git.pull(root.path) }
                output.ifBlank { "Pulled" }
            }
        }

    /** Pushes the current branch (Git panel's Push button). */
    fun push() =
        scope.launch {
            gitOperation {
                val output = remoteLock.withLock { git.push(root.path) }
                output.ifBlank { "Pushed" }
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
            // Both reload the branches.
            if (reloadFiles) synchronize() else refresh()
            actionLoad = branchLoadsAsked.get()
            true
        } catch (e: IOException) {
            message = e.message ?: "Git command failed"
            messageIsError = true
            refreshStatus()
            if (branchesChangedDuringAction) refreshBranchesAfterAction()
            false
        } finally {
            branchesChangedDuringAction = false
            busy = false
        }
    }
}

/** The preference holding the branches pinned in the project at [rootPath]. */
fun pinnedBranchesKey(rootPath: String): String = PreferenceKeys.PINNED_BRANCHES_PREFIX + rootPath

/** The branches pinned in the project at [rootPath], as [GitState.pinnedBranchesPreference] saved them. */
fun pinnedBranchesOf(preferences: Map<String, String>, rootPath: String): Set<String> {
    val saved = preferences[pinnedBranchesKey(rootPath)].orEmpty()
    return saved.lines().filter { it.isNotBlank() }.toSet()
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
