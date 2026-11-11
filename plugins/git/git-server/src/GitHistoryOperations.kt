package org.example.kotlineditor.git.server

import org.example.kotlineditor.model.GitResetMode
import java.io.IOException

private val COMMIT_NAME = Regex("[0-9a-fA-F]{4,64}")
private const val LOCAL_BRANCHES = "refs/heads/"
private const val REMOTE_BRANCHES = "refs/remotes/"
private const val CONFLICT_HINT = "stopped on conflicts: resolve them in the Git panel, then continue in the terminal"

// Refuses a commit name that could be read as an option or as anything but a (possibly abbreviated) hash.
private fun requireCommit(hash: String) {
    require(COMMIT_NAME.matches(hash)) { "Not a commit hash: $hash" }
}

// A branch or a commit: what a new branch may start from.
private fun requireStartPoint(startPoint: String) {
    require(isBranchName(startPoint) || COMMIT_NAME.matches(startPoint)) { "Not a branch or a commit: $startPoint" }
}

private fun refExists(root: String, ref: String): Boolean =
    gitOutput(root, "show-ref", "--verify", "--quiet", ref) != null

private fun requireLocalBranchExists(root: String, name: String) {
    if (!refExists(root, LOCAL_BRANCHES + name)) throw IOException("No branch named $name")
}

internal fun createBranchNow(root: String, name: String, startPoint: String, switchTo: Boolean) {
    requireBranchName(name)
    requireStartPoint(startPoint)
    if (refExists(root, LOCAL_BRANCHES + name)) throw IOException("A branch named $name already exists")
    if (switchTo) {
        runGitOnIndex(root, "checkout", "-b", name, startPoint)
    } else {
        runGitOnIndex(root, "branch", name, startPoint)
    }
}

internal fun renameBranchNow(root: String, oldName: String, newName: String) {
    requireBranchName(oldName)
    requireBranchName(newName)
    requireLocalBranchExists(root, oldName)
    if (refExists(root, LOCAL_BRANCHES + newName)) throw IOException("A branch named $newName already exists")
    runGitOnIndex(root, "branch", "-m", oldName, newName)
}

internal fun checkoutRemoteNow(root: String, remoteBranch: String): String {
    requireBranchName(remoteBranch)
    if (!refExists(root, REMOTE_BRANCHES + remoteBranch)) throw IOException("No remote branch named $remoteBranch")
    val local = remoteBranch.substringAfter('/')
    requireBranchName(local)
    if (refExists(root, LOCAL_BRANCHES + local)) {
        runGitOnIndex(root, "checkout", local)
    } else {
        runGitOnIndex(root, "checkout", "-b", local, "--track", remoteBranch)
    }
    return local
}

internal fun checkoutCommitNow(root: String, hash: String) {
    requireCommit(hash)
    runGitOnIndex(root, "checkout", "--detach", hash)
}

internal fun createTagNow(root: String, name: String, hash: String) {
    requireBranchName(name)
    requireCommit(hash)
    if (refExists(root, "refs/tags/$name")) throw IOException("A tag named $name already exists")
    runGitOnIndex(root, "tag", name, hash)
}

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
