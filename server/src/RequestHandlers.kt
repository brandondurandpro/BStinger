package org.example.kotlineditor.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.rpc.Method
import org.example.kotlineditor.rpc.blameJson
import org.example.kotlineditor.rpc.bool
import org.example.kotlineditor.rpc.branchesJson
import org.example.kotlineditor.rpc.commitsJson
import org.example.kotlineditor.rpc.diffJson
import org.example.kotlineditor.rpc.entry
import org.example.kotlineditor.rpc.int
import org.example.kotlineditor.rpc.keyEvent
import org.example.kotlineditor.rpc.long
import org.example.kotlineditor.rpc.matchesJson
import org.example.kotlineditor.rpc.read
import org.example.kotlineditor.rpc.session
import org.example.kotlineditor.rpc.string
import org.example.kotlineditor.rpc.stringOrNull
import org.example.kotlineditor.rpc.strings
import org.example.kotlineditor.rpc.symbolsJson
import org.example.kotlineditor.rpc.terminalInput
import org.example.kotlineditor.rpc.toJson
import org.example.kotlineditor.rpc.toJsonOrNull

private typealias Handler = suspend Request.() -> JsonElement

private const val PATH = "path"
private const val DIRECTORY = "directory"
private const val NAME = "name"
private const val ROOT = "root"
private const val BRANCH = "branch"
private const val LIMIT = "limit"
private const val TERMINAL = "terminal"

/** Implementation of each [Method]: read the named params, call the local services, return the JSON result. */
internal fun requestHandlers(services: LocalServices): Map<Method, Handler> =
    fileHandlers(services) +
        searchHandlers(services) +
        gitHandlers(services.git) +
        terminalHandlers() +
        languageServerHandlers(services)

private fun fileHandlers(services: LocalServices): Map<Method, Handler> {
    val fs = services.fileSystem
    return mapOf(
        Method.ResolveDirectory to { fs.resolveDirectory(params.string(PATH)).toJsonOrNull() },
        Method.UserHome to { JsonPrimitive(services.places.home()) },
        Method.ListDirectory to { fs.list(entry(DIRECTORY)).toJson() },
        Method.Read to {
            code = true
            fs.read(entry("file")).toJson()
        },
        Method.FileAt to { fs.fileAt(params.string(PATH)).toJsonOrNull() },
        Method.Write to { nothing { fs.write(entry("file"), params.string("text")) } },
        Method.CreateFile to { fs.createFile(entry(DIRECTORY), params.string(NAME)).toJson() },
        Method.CreateDirectory to { fs.createDirectory(entry(DIRECTORY), params.string(NAME)).toJson() },
        Method.Rename to { fs.rename(entry("entry"), params.string("newName")).toJson() },
        Method.Delete to { nothing { fs.delete(entry("entry")) } },
        Method.RecentLoad to { services.recentProjects.load().toJson() },
        Method.RecentSave to { nothing { services.recentProjects.save(params.read("paths") { it.strings() }) } },
        Method.SessionLoad to { services.sessions.load(params.string("project")).toJson() },
        Method.SessionSave to {
            val session = checkNotNull(params.read("session") { it.session() }) { "Missing session" }
            nothing { services.sessions.save(params.string("project"), session) }
        },
    )
}

private fun searchHandlers(services: LocalServices): Map<Method, Handler> {
    val search = services.search
    return mapOf(
        Method.WalkFiles to { search.walkFiles(entry(ROOT), params.int(LIMIT)).toJson() },
        Method.FindInFiles to {
            search
                .findInFiles(entry(ROOT), params.string("query"), params.bool("ignoreCase"), params.int(LIMIT))
                .matchesJson()
        },
        Method.Symbols to { search.symbols(entry(ROOT)).symbolsJson() },
    )
}

private fun gitHandlers(git: GitClient): Map<Method, Handler> =
    mapOf(
        Method.GitStatus to { git.status(root()).toJson() },
        Method.GitBranch to { JsonPrimitive(git.branch(root())) },
        Method.GitDiff to { git.diff(root(), params.string(PATH)).diffJson() },
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
        Method.GitCheckout to { nothing { git.checkout(root(), params.string(BRANCH)) } },
        Method.GitDeleteBranches to {
            nothing { git.deleteBranches(root(), params.read("branches") { it.strings() }) }
        },
        Method.GitCommit to {
            nothing {
                git.commit(root(), params.string("message"), params.read("paths") { it.strings() })
            }
        },
        Method.GitPull to { JsonPrimitive(git.pull(root())) },
        Method.GitPush to { JsonPrimitive(git.push(root())) },
        Method.GitPushBranch to { JsonPrimitive(git.pushBranch(root(), params.string(BRANCH))) },
        Method.GitPullBranch to { JsonPrimitive(git.pullBranch(root(), params.string(BRANCH))) },
        Method.GitWorktree to { JsonPrimitive(git.worktree(root(), params.string("branch"))) },
        Method.GitRemoveWorktree to { nothing { git.removeWorktree(root(), params.string(PATH)) } },
    )

private fun terminalHandlers(): Map<Method, Handler> =
    mapOf(
        Method.TerminalStart to {
            nothing {
                terminals.start(
                    id,
                    params.string(DIRECTORY),
                    params.int("columns"),
                    params.int("rows"),
                    params.int("cellWidthPx"),
                    params.int("cellHeightPx"),
                )
            }
        },
        Method.TerminalWrite to { nothing { terminalOrFail().write(params.terminalInput()) } },
        Method.TerminalKey to { nothing { terminalOrFail().sendKey(params.read("event") { it.keyEvent() }) } },
        Method.TerminalPaste to { nothing { terminalOrFail().paste(params.string("text")) } },
        Method.TerminalResize to {
            val terminal = terminalOrFail()
            nothing {
                terminal.resize(
                    params.int("columns"),
                    params.int("rows"),
                    params.int("cellWidthPx"),
                    params.int("cellHeightPx"),
                )
            }
        },
        Method.TerminalScroll to { nothing { terminalOrFail().scroll(params.int("delta")) } },
        Method.TerminalClear to { nothing { terminalOrFail().clear() } },
        Method.TerminalResume to { nothing { terminals[params.long(TERMINAL)]?.resume(params.long("lastSeq")) } },
        Method.TerminalDestroy to { nothing { terminals.stop(params.long(TERMINAL)) } },
    )

// A method that returns nothing answers with a null result.
private inline fun nothing(block: () -> Unit): JsonElement {
    block()
    return JsonNull
}

private fun Request.entry(name: String) = params.read(name) { it.entry() }

private fun Request.root() = params.string(ROOT)

private fun Request.terminalOrFail(): ServerTerminal =
    checkNotNull(terminals[params.long(TERMINAL)]) { "This terminal has ended" }
