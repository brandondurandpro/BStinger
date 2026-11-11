package org.example.kotlineditor.git.server

import java.io.IOException

private val COMMIT_NAME = Regex("[0-9a-fA-F]{4,64}")
private const val LOCAL_BRANCHES = "refs/heads/"
private const val REMOTE_BRANCHES = "refs/remotes/"
private const val CHECKOUT = "checkout"

// Refuses a commit name that could be read as an option or as anything but a (possibly abbreviated) hash.
internal fun requireCommit(hash: String) {
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
        runGitOnIndex(root, CHECKOUT, "-b", name, startPoint)
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
        runGitOnIndex(root, CHECKOUT, local)
    } else {
        runGitOnIndex(root, CHECKOUT, "-b", local, "--track", remoteBranch)
    }
    return local
}

internal fun checkoutCommitNow(root: String, hash: String) {
    requireCommit(hash)
    runGitOnIndex(root, CHECKOUT, "--detach", hash)
}

internal fun createTagNow(root: String, name: String, hash: String) {
    requireBranchName(name)
    requireCommit(hash)
    if (refExists(root, "refs/tags/$name")) throw IOException("A tag named $name already exists")
    runGitOnIndex(root, "tag", name, hash)
}
