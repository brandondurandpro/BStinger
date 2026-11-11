package org.example.kotlineditor.git.server

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.blameJson
import org.example.kotlineditor.rpc.branchesJson
import org.example.kotlineditor.rpc.commitsJson
import org.example.kotlineditor.rpc.diffJson
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.stringOrNull
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.textContent
import org.example.kotlineditor.rpc.toJson
import org.example.kotlineditor.server.Handler
import org.example.kotlineditor.server.LocalServices
import org.example.kotlineditor.server.Request
import org.example.kotlineditor.server.ServerContribution
import org.example.kotlineditor.server.nothing

private const val PATH = "path"
private const val ROOT = "root"
private const val BRANCH = "branch"
private const val LIMIT = "limit"
private const val PATHS = "paths"

/** Found by the server through `META-INF/services`. */
class GitServerContribution : ServerContribution {
    override fun handlers(services: LocalServices): Map<MethodSpec, Handler> {
        services.agentWorktrees = ::agentWorktrees
        return gitHandlers(CliGitClient())
    }
}

private fun Request.root() = params.string(ROOT)

internal fun gitHandlers(git: GitClient): Map<MethodSpec, Handler> =
    mapOf(
        Method.GitStatus to { git.status(root()).toJson() },
        Method.GitBranch to { JsonPrimitive(git.branch(root())) },
        Method.GitDiff to { git.diff(root(), params.string(PATH)).diffJson() },
        Method.GitHeadLines to {
            val lines = git.headLines(root(), params.string(PATH))
            if (lines == null) JsonNull else lines.toJson()
        },
        Method.GitBlame to { git.blame(root(), params.string(PATH)).blameJson() },
        Method.GitRemoteBranches to { git.remoteBranches(root()).toJson() },
        Method.GitLog to {
            git.log(root(), params.string("ref"), params.int("skip"), params.int(LIMIT)).commitsJson()
        },
        Method.GitSearchLog to {
            git
                .searchLog(root(), params.string("ref"), params.string("query"), params.int("skip"), params.int(LIMIT))
                .commitsJson()
        },
        Method.GitCommitDiff to {
            git
                .commitDiff(root(), params.string("hash"), params.string(PATH), params.stringOrNull("oldPath"))
                .diffJson()
        },
        Method.GitCommitDetails to { git.commitDetails(root(), params.string("hash")).toJson() },
        Method.GitBranchDetails to { git.branchDetails(root()).branchesJson() },
        Method.GitBranches to { git.branches(root()).toJson() },
        Method.GitMergeSides to { git.mergeSides(root(), params.string(PATH)).toJson() },
        Method.GitMergeHistory to { git.mergeHistory(root(), params.string(PATH)).toJson() },
        Method.GitResolveConflict to {
            nothing { git.resolveConflict(root(), params.string(PATH), params.read("result") { it.textContent() }) }
        },
        Method.GitCheckout to { nothing { git.checkout(root(), params.string(BRANCH)) } },
        Method.GitDeleteBranches to {
            nothing { git.deleteBranches(root(), params.read("branches") { it.strings() }) }
        },
        Method.GitRevert to { nothing { git.revert(root(), params.read(PATHS) { it.strings() }) } },
        Method.GitCommit to {
            nothing {
                git.commit(root(), params.string("message"), params.read(PATHS) { it.strings() })
            }
        },
        Method.GitPull to { JsonPrimitive(git.pull(root())) },
        Method.GitPush to { JsonPrimitive(git.push(root())) },
        Method.GitFetch to { nothing { git.fetch(root()) } },
        Method.GitPushBranch to { JsonPrimitive(git.pushBranch(root(), params.string(BRANCH))) },
        Method.GitPullBranch to { JsonPrimitive(git.pullBranch(root(), params.string(BRANCH))) },
        Method.GitWorktree to { JsonPrimitive(git.worktree(root(), params.string("branch"))) },
        Method.GitRemoveWorktree to { nothing { git.removeWorktree(root(), params.string(PATH)) } },
    )
