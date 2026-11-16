package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AheadBehind
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.GitResetMode
import org.example.kotlineditor.model.MergeHistory
import org.example.kotlineditor.model.MergeSides
import org.example.kotlineditor.model.WorktreeClient

// JSON of Git data. Enums travel by name.

private const val BRANCH = "branch"
private const val LIMIT = "limit"
private const val PATH = "path"
private const val PATHS = "paths"
private const val ROOT = "root"
private const val NAME = "name"
private const val CURRENT = "current"
private const val UPSTREAM = "upstream"
private const val AHEAD = "ahead"
private const val BEHIND = "behind"
private const val UPSTREAM_GONE = "upstreamGone"

/** A Git status map: `{"path": "Modified", ...}`. */
fun Map<String, GitFileStatus>.toJson(): JsonObject = JsonObject(mapValues { JsonPrimitive(it.value.name) })

/** Reads a Git status map. */
fun JsonElement.gitStatus(): Map<String, GitFileStatus> =
    jsonObject.mapValues { GitFileStatus.valueOf(it.value.string()) }

/** Local branches and their remote synchronization. */
fun List<GitBranchInfo>.branchesJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put(NAME, it.name)
                put(CURRENT, it.current)
                put(UPSTREAM, it.upstream?.let(::JsonPrimitive) ?: JsonNull)
                put(AHEAD, it.ahead)
                put(BEHIND, it.behind)
                put(UPSTREAM_GONE, it.upstreamGone)
            }
        },
    )

/** Reads local branches and their remote synchronization. */
fun JsonElement.branchInfos(): List<GitBranchInfo> =
    jsonArray.map {
        val branch = it.jsonObject
        GitBranchInfo(
            name = branch.string(NAME),
            current = branch.bool(CURRENT),
            upstream = branch.stringOrNull("upstream"),
            ahead = branch.int(AHEAD),
            behind = branch.int(BEHIND),
            upstreamGone = branch.bool("upstreamGone"),
        )
    }

/** The lines of a diff. */
fun List<DiffLine>.diffJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put("kind", it.kind.name)
                put("oldLine", it.oldLine)
                put("newLine", it.newLine)
                put("text", it.text)
            }
        },
    )

/** Reads the lines of a diff. */
fun JsonElement.diffLines(): List<DiffLine> =
    jsonArray.map {
        val line = it.jsonObject
        DiffLine(DiffKind.valueOf(line.string("kind")), line.int("oldLine"), line.int("newLine"), line.string("text"))
    }

/** Blame annotations. */
fun List<BlameLine>.blameJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put("line", it.line)
                put("commit", it.commit)
                put("author", it.author)
                put("date", it.date)
            }
        },
    )

/** Reads blame annotations. */
fun JsonElement.blameLines(): List<BlameLine> =
    jsonArray.map {
        val line = it.jsonObject
        BlameLine(line.int("line"), line.string("commit"), line.string("author"), line.string("date"))
    }

/** [GitClient] of the server. */
@Suppress("TooManyFunctions") // one per Git operation of the protocol
class RemoteGit(private val client: RpcClient) :
    GitClient,
    WorktreeClient by RemoteWorktrees(client) {
    override suspend fun status(root: String): Map<String, GitFileStatus> =
        client.call(Method.GitStatus) { put(ROOT, root) }.gitStatus()

    override suspend fun branch(root: String): String? =
        client.call(Method.GitBranch) { put(ROOT, root) }.stringOrNull()

    override suspend fun diff(root: String, path: String): List<DiffLine> =
        client.call(Method.GitDiff) { file(root, path) }.diffLines()

    override suspend fun branchDetails(root: String): List<GitBranchInfo> =
        client.call(Method.GitBranchDetails) { put(ROOT, root) }.branchInfos()

    override suspend fun branches(root: String): List<String> =
        client.call(Method.GitBranches) { put(ROOT, root) }.strings()

    override suspend fun mergeSides(root: String, path: String): MergeSides {
        val sides =
            client.call(Method.GitMergeSides) {
                put(ROOT, root)
                put(PATH, path)
            }
        return sides.mergeSides()
    }

    override suspend fun mergeHistory(root: String, path: String): MergeHistory {
        val history =
            client.call(Method.GitMergeHistory) {
                put(ROOT, root)
                put(PATH, path)
            }
        return history.mergeHistory()
    }

    override suspend fun resolveConflict(root: String, path: String, result: FileContent.Text) {
        client.call(Method.GitResolveConflict) {
            put(ROOT, root)
            put(PATH, path)
            put("result", result.toJson())
        }
    }

    override suspend fun checkout(root: String, branch: String) {
        client.call(Method.GitCheckout) {
            put(ROOT, root)
            put(BRANCH, branch)
        }
    }

    override suspend fun deleteBranches(root: String, branches: List<String>) {
        client.call(Method.GitDeleteBranches) {
            put(ROOT, root)
            put("branches", branches.toJson())
        }
    }

    override suspend fun revert(root: String, paths: List<String>) {
        client.call(Method.GitRevert) {
            put(ROOT, root)
            put(PATHS, paths.toJson())
        }
    }

    override suspend fun commit(root: String, message: String, paths: List<String>) {
        client.call(Method.GitCommit) {
            put(ROOT, root)
            put("message", message)
            put(PATHS, paths.toJson())
        }
    }

    override suspend fun pull(root: String): String = client.call(Method.GitPull) { put(ROOT, root) }.string()

    override suspend fun push(root: String): String = client.call(Method.GitPush) { put(ROOT, root) }.string()

    override suspend fun fetch(root: String) {
        client.call(Method.GitFetch) { put(ROOT, root) }
    }

    override suspend fun pushBranch(root: String, branch: String): String {
        val output =
            client.call(Method.GitPushBranch) {
                put(ROOT, root)
                put(BRANCH, branch)
            }
        return output.string()
    }

    override suspend fun pullBranch(root: String, branch: String): String {
        val output =
            client.call(Method.GitPullBranch) {
                put(ROOT, root)
                put(BRANCH, branch)
            }
        return output.string()
    }

    override suspend fun createBranch(root: String, name: String, startPoint: String, switchTo: Boolean) {
        client.call(Method.GitCreateBranch) {
            put(ROOT, root)
            put(BRANCH, name)
            put("startPoint", startPoint)
            put("switch", switchTo)
        }
    }

    override suspend fun renameBranch(root: String, oldName: String, newName: String) {
        client.call(Method.GitRenameBranch) {
            put(ROOT, root)
            put(BRANCH, oldName)
            put("newName", newName)
        }
    }

    override suspend fun checkoutRemote(root: String, remoteBranch: String): String {
        val output =
            client.call(Method.GitCheckoutRemote) {
                put(ROOT, root)
                put(BRANCH, remoteBranch)
            }
        return output.string()
    }

    override suspend fun checkoutCommit(root: String, hash: String) {
        client.call(Method.GitCheckoutCommit) {
            put(ROOT, root)
            put("hash", hash)
        }
    }

    override suspend fun createTag(root: String, name: String, hash: String) {
        client.call(Method.GitCreateTag) {
            put(ROOT, root)
            put(NAME, name)
            put("hash", hash)
        }
    }

    override suspend fun merge(root: String, branch: String): String {
        val output =
            client.call(Method.GitMerge) {
                put(ROOT, root)
                put(BRANCH, branch)
            }
        return output.string()
    }

    override suspend fun rebase(root: String, onto: String): String {
        val output =
            client.call(Method.GitRebase) {
                put(ROOT, root)
                put(BRANCH, onto)
            }
        return output.string()
    }

    override suspend fun cherryPick(root: String, hash: String): String {
        val output =
            client.call(Method.GitCherryPick) {
                put(ROOT, root)
                put("hash", hash)
            }
        return output.string()
    }

    override suspend fun revertCommit(root: String, hash: String): String {
        val output =
            client.call(Method.GitRevertCommit) {
                put(ROOT, root)
                put("hash", hash)
            }
        return output.string()
    }

    override suspend fun reset(root: String, hash: String, mode: GitResetMode): String {
        val output =
            client.call(Method.GitReset) {
                put(ROOT, root)
                put("hash", hash)
                put("mode", mode.name)
            }
        return output.string()
    }

    override suspend fun headLines(root: String, path: String): List<String>? {
        val answer = client.call(Method.GitHeadLines) { file(root, path) }
        return if (answer is JsonNull) null else answer.strings()
    }

    override suspend fun blame(root: String, path: String): List<BlameLine> =
        client.call(Method.GitBlame) { file(root, path) }.blameLines()

    override suspend fun remoteBranches(root: String): List<String> =
        client.call(Method.GitRemoteBranches) { put(ROOT, root) }.strings()

    override suspend fun defaultRemoteBranch(root: String): String? =
        client.call(Method.GitDefaultRemoteBranch) { put(ROOT, root) }.stringOrNull()

    override suspend fun log(root: String, ref: String, skip: Int, limit: Int): List<GitCommit> {
        val page =
            client.call(Method.GitLog) {
                put(ROOT, root)
                put("ref", ref)
                put("skip", skip)
                put(LIMIT, limit)
            }
        return page.commits()
    }

    override suspend fun aheadBehind(root: String, base: String): AheadBehind {
        val answer =
            client.call(Method.GitAheadBehind) {
                put(ROOT, root)
                put("base", base)
            }
        return AheadBehind(answer.jsonObject.int(AHEAD), answer.jsonObject.int(BEHIND))
    }

    override suspend fun searchLog(root: String, ref: String, query: String, skip: Int, limit: Int): List<GitCommit> {
        val page =
            client.call(Method.GitSearchLog) {
                put(ROOT, root)
                put("ref", ref)
                put("query", query)
                put("skip", skip)
                put(LIMIT, limit)
            }
        return page.commits()
    }

    override suspend fun commitDiff(root: String, hash: String, path: String, oldPath: String?): List<DiffLine> {
        val diff =
            client.call(Method.GitCommitDiff) {
                put(ROOT, root)
                put("hash", hash)
                put(PATH, path)
                put("oldPath", oldPath?.let(::JsonPrimitive) ?: JsonNull)
            }
        return diff.diffLines()
    }

    override suspend fun commitDetails(root: String, hash: String): GitCommitDetails {
        val details =
            client.call(Method.GitCommitDetails) {
                put(ROOT, root)
                put("hash", hash)
            }
        return details.commitDetails()
    }

    private fun JsonObjectBuilder.file(root: String, path: String) {
        put(ROOT, root)
        put(PATH, path)
    }
}

/** [WorktreeClient] of the server. */
class RemoteWorktrees(private val client: RpcClient) : WorktreeClient {
    override suspend fun worktree(root: String, branch: String, base: String?): String {
        val result =
            client.call(Method.GitWorktree) {
                put(ROOT, root)
                put("branch", branch)
                if (base != null) put("base", base)
            }
        return result.string()
    }

    override suspend fun removeWorktree(root: String, path: String) {
        client.call(Method.GitRemoveWorktree) {
            put(ROOT, root)
            put(PATH, path)
        }
    }
}
