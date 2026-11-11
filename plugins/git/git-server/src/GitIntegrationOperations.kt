package org.example.kotlineditor.git.server

import org.example.kotlineditor.model.GitResetMode
import java.io.IOException

private const val CONFLICT_HINT = "stopped on conflicts: resolve them in the Git panel, then continue in the terminal"

internal fun mergeNow(root: String, branch: String): String {
    requireBranchName(branch)
    return runStoppingOnConflicts(root, "merge", "--no-edit", branch)
}

internal fun rebaseNow(root: String, onto: String): String {
    requireBranchName(onto)
    return runStoppingOnConflicts(root, "rebase", onto)
}

internal fun cherryPickNow(root: String, hash: String): String {
    requireCommit(hash)
    requireNotMerge(root, hash)
    return runStoppingOnConflicts(root, "cherry-pick", hash)
}

internal fun revertCommitNow(root: String, hash: String): String {
    requireCommit(hash)
    requireNotMerge(root, hash)
    return runStoppingOnConflicts(root, "revert", "--no-edit", hash)
}

internal fun resetNow(root: String, hash: String, mode: GitResetMode): String {
    requireCommit(hash)
    val flag = "--" + mode.name.lowercase()
    return runGitOnIndex(root, "reset", flag, hash)
}

// A merge commit needs the parent to follow, which the menu cannot ask for: Git would refuse it anyway.
private fun requireNotMerge(root: String, hash: String) {
    val parents = gitOutput(root, "rev-list", "--parents", "-n", "1", hash)?.firstOrNull()?.trim().orEmpty()
    val parentCount = parents.split(' ').filter { it.isNotEmpty() }.size - 1
    if (parentCount > 1) throw IOException("$hash is a merge commit")
}

// A command that stops on conflicts leaves the unmerged files to the merge editor: that is a result to show, not an
// error. Any other failure keeps Git's message.
private fun runStoppingOnConflicts(root: String, vararg args: String): String =
    try {
        runGitOnIndex(root, *args)
    } catch (failure: IOException) {
        if (hasUnmergedFiles(root)) "git ${args.first()} $CONFLICT_HINT" else throw failure
    }

private fun hasUnmergedFiles(root: String): Boolean =
    gitOutput(root, "diff", "--name-only", "--diff-filter=U").orEmpty().any { it.isNotBlank() }
