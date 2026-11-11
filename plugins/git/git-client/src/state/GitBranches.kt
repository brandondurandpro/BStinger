package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.example.kotlineditor.model.GitBranchInfo

/**
 * The local branches of a project as [GitState] shows them: the list, the branches pinned in the toolbar (saved with
 * the preferences of the project) and the branches ticked in the picker for a bulk action.
 */
@Stable
internal class GitBranches(private val rootPath: String, pinned: Set<String>) {
    var names: List<String> by mutableStateOf(emptyList())
        private set

    var infos: List<GitBranchInfo> by mutableStateOf(emptyList())
        private set

    var pinned: Set<String> by mutableStateOf(pinned)
        private set

    var picked: Set<String> by mutableStateOf(emptySet())
        private set

    /** Shows [branches], read from the repository; pins and picks of branches that are gone are dropped. */
    fun apply(branches: List<GitBranchInfo>) {
        infos = sorted(branches)
        names = branches.map { it.name }
        val present = branches.mapTo(HashSet()) { it.name }
        // A repository has a branch once it has a commit: an empty list is a failed read, which must not lose the pins.
        if (present.isNotEmpty()) pinned = pinned intersect present
        picked = picked intersect present
    }

    /** The saved form of [pinned]: one branch name per line (Git forbids line breaks in them). */
    fun pinnedPreference(): Pair<String, String> = pinnedBranchesKey(rootPath) to pinned.joinToString("\n")

    fun togglePinned(name: String) {
        pinned = if (name in pinned) pinned - name else pinned + name
    }

    fun togglePicked(name: String) {
        picked = if (name in picked) picked - name else picked + name
    }

    fun clearPicks() {
        picked = emptySet()
    }

    fun pick(branches: Collection<String>) {
        picked = picked + branches
    }

    // Branches just deleted: they leave the toolbar and the picks at once, before the refresh lists the others.
    fun forget(branches: Set<String>) {
        pinned = pinned - branches
        picked = picked - branches
        infos = infos.filter { it.name !in branches }
        names = names.filter { it !in branches }
    }

    fun pinPicked() {
        pinned = pinned + picked
    }

    fun unpinPicked() {
        pinned = pinned - picked
    }

    /** Local branches whose remote is missing or gone, other than the current one. */
    fun withoutRemote(): List<GitBranchInfo> = infos.filter { !it.current && !it.hasUpstream }

    // Current branch first, then by name: the branch picker and the toolbar chips keep a stable, familiar order.
    private fun sorted(branches: List<GitBranchInfo>) =
        branches.sortedWith(compareByDescending<GitBranchInfo> { it.current }.thenBy { it.name })
}

/** The branch [name] as an older server lists it: a name, without upstream. */
internal fun branchInfoOf(name: String, currentBranch: String?) =
    GitBranchInfo(name = name, current = name == currentBranch, upstream = null, ahead = 0, behind = 0)

/** The preference holding the branches pinned in the project at [rootPath]. */
fun pinnedBranchesKey(rootPath: String): String = PreferenceKeys.PINNED_BRANCHES_PREFIX + rootPath

/** The branches pinned in the project at [rootPath], as [GitState.pinnedBranchesPreference] saved them. */
fun pinnedBranchesOf(preferences: Map<String, String>, rootPath: String): Set<String> {
    val saved = preferences[pinnedBranchesKey(rootPath)].orEmpty()
    return saved.lines().filter { it.isNotBlank() }.toSet()
}
