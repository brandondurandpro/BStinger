package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails

/** Commits loaded per request: a screen and a half, so that scrolling asks for the next page before the end. */
const val HISTORY_PAGE = 100

/**
 * The Branches tool window: every local and remote branch, the history of the selected one, loaded page by page as
 * the list scrolls, and the details of the selected commit. An answer for a branch or a commit the user has left
 * since is dropped, so a slow link never shows the history of another branch.
 */
@Stable
class BranchHistoryState(private val root: String, private val git: GitClient, private val scope: CoroutineScope) {
    /** Remote-tracking branches (`origin/main`); local ones come from [ProjectState.gitBranchInfos]. */
    var remoteBranches: List<String> by mutableStateOf(emptyList())
        private set

    /** Branch whose history is shown; null until the window first picks the current branch. */
    var selectedRef: String? by mutableStateOf(null)
        private set

    /** Commits of [selectedRef] loaded so far, newest first. */
    var commits: List<GitCommit> by mutableStateOf(emptyList())
        private set

    /** True while a page is being loaded. */
    var loading by mutableStateOf(false)
        private set

    /** True once the oldest commit is loaded. */
    var complete by mutableStateOf(false)
        private set

    /** Why the history could not be loaded (server unreachable, ref deleted), or null. */
    var error: String? by mutableStateOf(null)
        private set

    /** Commit whose details are shown. */
    var selectedCommit: GitCommit? by mutableStateOf(null)
        private set

    /** Message and files of [selectedCommit], null while they load. */
    var details: GitCommitDetails? by mutableStateOf(null)
        private set

    /** Filter of the branch list. */
    var branchQuery by mutableStateOf("")

    // Incremented by every selection or reload: an answer of an older generation is dropped.
    private var generation = 0
    private var pageJob: Job? = null
    private var detailsJob: Job? = null

    /** Shows the history of [ref] from its newest commit (a click on a branch, Show History of a pinned branch). */
    fun select(ref: String) {
        selectedRef = ref
        reload()
    }

    /** Selects [ref] only when nothing is selected yet: the window opens on the current branch. */
    fun selectIfNone(ref: String?) {
        if (selectedRef == null && ref != null) select(ref)
    }

    /** Reloads the remote branches and the first page of the selected branch (the window's refresh button). */
    fun reload() {
        scope.launch { runCatching { git.remoteBranches(root) }.onSuccess { remoteBranches = it } }
        generation++
        pageJob?.cancel()
        commits = emptyList()
        complete = false
        error = null
        loading = false
        clearCommit()
        loadMore()
    }

    /** Loads the next page, unless one is loading or the history is complete (the list nears its end). */
    fun loadMore() {
        val ref = selectedRef ?: return
        if (loading || complete) return
        loading = true
        val skip = commits.size
        val asked = generation
        pageJob =
            scope.launch {
                val page = runCatching { git.log(root, ref, skip, HISTORY_PAGE) }
                ensureActive()
                // A newer selection or reload replaced this request: its answer is out of date.
                if (asked == generation) applyPage(ref, page)
            }
    }

    private fun applyPage(ref: String, page: Result<List<GitCommit>>) {
        val loaded = page.getOrNull()
        if (loaded != null) {
            commits = commits + loaded
            complete = loaded.size < HISTORY_PAGE
        } else {
            error = page.exceptionOrNull()?.message ?: "Cannot read the history of $ref"
        }
        loading = false
    }

    /** Shows the message and the files of [commit]. */
    fun selectCommit(commit: GitCommit) {
        selectedCommit = commit
        details = null
        detailsJob?.cancel()
        detailsJob =
            scope.launch {
                val loaded = runCatching { git.commitDetails(root, commit.hash) }
                ensureActive()
                loaded.onSuccess {
                    if (selectedCommit?.hash == commit.hash) details = it
                }
            }
    }

    fun clearCommit() {
        detailsJob?.cancel()
        selectedCommit = null
        details = null
    }
}
