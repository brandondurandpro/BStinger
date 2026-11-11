package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.MethodSpec
import org.example.kotlineditor.rpc.blameJson
import org.example.kotlineditor.rpc.bool
import org.example.kotlineditor.rpc.branchesJson
import org.example.kotlineditor.rpc.commitsJson
import org.example.kotlineditor.rpc.diffJson
import org.example.kotlineditor.rpc.diskCursor
import org.example.kotlineditor.rpc.entry
import org.example.kotlineditor.rpc.fileVersion
import org.example.kotlineditor.rpc.findOptions
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.matchesJson
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.session
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.stringOrNull
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.symbolsJson
import org.example.kotlineditor.rpc.textContent
import org.example.kotlineditor.rpc.toJson
import org.example.kotlineditor.rpc.toJsonOrNull

typealias Handler = suspend Request.() -> JsonElement

private const val PATH = "path"
private const val PROJECT = "project"
private const val DIRECTORY = "directory"
private const val NAME = "name"
private const val ROOT = "root"
private const val PATHS = "paths"
private const val BRANCH = "branch"
private const val LIMIT = "limit"
private const val TERMINAL = "terminal"
private const val STREAM = "stream"
private const val VERSION = "version"

internal fun fileHandlers(services: LocalServices): Map<MethodSpec, Handler> {
    val fs = services.fileSystem
    val disk = DiskNotifications(services)
    return mapOf(
        Method.ResolveDirectory to { fs.resolveDirectory(params.string(PATH)).toJsonOrNull() },
        Method.UserHome to { JsonPrimitive(services.places.home()) },
        Method.ListDirectory to { fs.list(entry(DIRECTORY)).toJson() },
        Method.Read to {
            code = true
            fs.read(entry("file")).toJson()
        },
        Method.FileAt to { fs.fileAt(params.string(PATH)).toJsonOrNull() },
        Method.Write to {
            val file = entry("file")
            val expected =
                if (VERSION in params) {
                    params.read(VERSION) { it.fileVersion() }
                } else {
                    null
                }
            // The version of the file once the write landed, or nothing when it was dropped (a newer save of the
            // same file already ran): the client replays its older request and is answered from the cache.
            val version = fs.write(file, params.string("text"), writer = session, order = id, expected = expected)
            disk.written(file)
            version?.toJson() ?: JsonNull
        },
        Method.CreateFile to { fs.createFile(entry(DIRECTORY), params.string(NAME)).also(disk::created).toJson() },
        Method.CreateDirectory to {
            fs.createDirectory(entry(DIRECTORY), params.string(NAME)).also(disk::created).toJson()
        },
        Method.Rename to {
            val old = entry("entry")
            val before = disk.filesOf(old)
            fs.rename(old, params.string("newName")).also { disk.renamed(before, old, it) }.toJson()
        },
        Method.Delete to {
            val deleted = entry("entry")
            val before = disk.filesOf(deleted)
            nothing {
                fs.delete(deleted)
                disk.deleted(before, deleted)
            }
        },
        Method.DiskChanges to {
            val open = open()
            services.disk.changes(params.string(ROOT), params.read("since") { it.diskCursor() }, open).toJson()
        },
        Method.RecentLoad to { services.recentProjects.load().toJson() },
        Method.RecentSave to { nothing { services.recentProjects.save(params.read(PATHS) { it.strings() }) } },
        Method.SessionLoad to { services.sessions.load(params.string(PROJECT)).toJson() },
        Method.SessionSave to {
            val session = checkNotNull(params.read("session") { it.session() }) { "Missing session" }
            nothing { services.sessions.save(params.string(PROJECT), session) }
        },
    )
}

internal fun searchHandlers(services: LocalServices): Map<MethodSpec, Handler> {
    val search = services.search
    return mapOf(
        Method.WalkFiles to { search.walkFiles(entry(ROOT), params.int(LIMIT)).toJson() },
        Method.FindInFiles to {
            val root = entry(ROOT)
            search
                .findInFiles(root, params.findOptions(), params.int(LIMIT))
                .matchesJson()
        },
        Method.FindInFilesStream to {
            val root = entry(ROOT)
            val options = params.findOptions()
            val limit = params.int(LIMIT)
            nothing { streams.start(id, search, root, options, limit) }
        },
        Method.FindInFilesResume to { nothing { streams.resume(params.long(STREAM), params.long("lastSeq")) } },
        Method.Symbols to { search.symbols(entry(ROOT)).symbolsJson() },
    )
}

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

/** Runs [block] for a method that returns nothing: it answers with a null result. */
inline fun nothing(block: () -> Unit): JsonElement {
    block()
    return JsonNull
}

internal fun Request.entry(name: String) = params.read(name) { it.entry() }

// `fs/changes`: the open documents the client holds (an old client names none, and answers as today).
private fun Request.open() = params.get("open")?.let { fields -> fields.strings() } ?: emptyList()

private fun Request.root() = params.string(ROOT)

/** `telemetry/config`: the client says whether the user agreed to share performance measures. */
internal fun telemetryHandlers(telemetry: ServerTelemetry): Map<MethodSpec, Handler> =
    mapOf(
        Method.TelemetryConfig to {
            telemetry.configure(params.bool("enabled"), params.stringOrNull("instanceId"))
            JsonNull
        },
    )
