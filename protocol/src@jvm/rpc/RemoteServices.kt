package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.BlameLine
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.DirectoryPicker
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.GitBranchInfo
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitDetails
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.RecentProjectsStore
import org.example.kotlineditor.model.SearchInterruptedException
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.SearchUpdate
import org.example.kotlineditor.model.SessionStore
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalLauncher
import org.example.kotlineditor.model.TerminalProcess
import org.example.kotlineditor.model.TerminalSnapshot
import java.io.IOException

private const val ROOT = "root"
private const val LIMIT = "limit"
private const val BRANCH = "branch"
private const val DIRECTORY = "directory"
private const val ENTRY = "entry"
private const val FILE = "file"
private const val PATH = "path"
private const val NAME = "name"
private const val TERMINAL = "terminal"
private const val MATCHES = "matches"

/** Every IDE service, backed by a server. */
fun RpcClient.appServices(directoryPicker: DirectoryPicker): AppServices =
    AppServices(
        fileSystem = RemoteFileSystem(this),
        search = RemoteSearch(this),
        recentProjects = RemoteRecentProjects(this),
        directoryPicker = directoryPicker,
        terminal = RemoteTerminalLauncher(this),
        sessions = RemoteSessions(this),
        git = RemoteGit(this),
        connection = status,
        network = network,
    )

/** [ProjectFileSystem] of the server. */
class RemoteFileSystem(private val client: RpcClient) : ProjectFileSystem {
    override suspend fun resolveDirectory(path: String): FileEntry? =
        client.call(Method.ResolveDirectory) { put(PATH, path) }.entryOrNull()

    override suspend fun list(directory: FileEntry): List<FileEntry> =
        client.call(Method.ListDirectory) { put(DIRECTORY, directory.toJson()) }.entries()

    override suspend fun read(file: FileEntry): FileContent =
        client.call(Method.Read) { put(FILE, file.toJson()) }.content()

    override suspend fun fileAt(path: String): FileEntry? = client.call(Method.FileAt) { put(PATH, path) }.entryOrNull()

    override suspend fun write(file: FileEntry, text: String) {
        client.call(Method.Write) {
            put(FILE, file.toJson())
            put("text", text)
        }
    }

    override suspend fun createFile(directory: FileEntry, name: String): FileEntry {
        val result =
            client.call(Method.CreateFile) {
                put(DIRECTORY, directory.toJson())
                put(NAME, name)
            }
        return result.entry()
    }

    override suspend fun createDirectory(directory: FileEntry, name: String): FileEntry {
        val result =
            client.call(Method.CreateDirectory) {
                put(DIRECTORY, directory.toJson())
                put(NAME, name)
            }
        return result.entry()
    }

    override suspend fun rename(entry: FileEntry, newName: String): FileEntry {
        val result =
            client.call(Method.Rename) {
                put(ENTRY, entry.toJson())
                put("newName", newName)
            }
        return result.entry()
    }

    override suspend fun delete(entry: FileEntry) {
        client.call(Method.Delete) { put(ENTRY, entry.toJson()) }
    }
}

/** [ProjectSearch] of the server: only the results travel. */
class RemoteSearch(private val client: RpcClient) : ProjectSearch {
    override suspend fun walkFiles(root: FileEntry, limit: Int): List<FileEntry> {
        val result =
            client.call(Method.WalkFiles) {
                put(ROOT, root.toJson())
                put(LIMIT, limit)
            }
        return result.entries()
    }

    /**
     * The request id of FindInFiles is the id of its stream of matches, registered before the request is sent.
     * The stream is abandoned (the server stops searching) as soon as the collection stops; it fails with
     * [StreamLostException] when the server restarted during the search.
     */
    override fun findInFiles(root: FileEntry, query: SearchQuery, limit: Int): Flow<SearchUpdate> =
        flow {
            val id = client.newId()
            val updates = client.openStream(id, StreamKind.Appending)
            try {
                client.call(Method.FindInFiles, id = id) {
                    put(ROOT, root.toJson())
                    put("query", query.toJson())
                    put(LIMIT, limit)
                }
                updates.receiveOrLost().collect { message ->
                    when (message) {
                        is StreamMessage.Event -> {
                            emit(SearchUpdate.Found(message.payload.field(MATCHES).matches()))
                        }

                        is StreamMessage.End -> {
                            message.payload.stringOrNull("error")?.let { throw RemoteException(it) }
                            emit(message.payload.searchFinished())
                        }
                    }
                }
            } finally {
                client.abandonStream(id)
            }
        }

    override suspend fun symbols(root: FileEntry): List<FileSymbol> =
        client.call(Method.Symbols) { put(ROOT, root.toJson()) }.symbols()
}

// The messages of a stream, failing with SearchInterruptedException when the server lost it.
private fun ReceiveChannel<StreamMessage>.receiveOrLost(): Flow<StreamMessage> =
    flow {
        try {
            for (message in this@receiveOrLost) emit(message)
        } catch (_: StreamLostException) {
            throw SearchInterruptedException()
        }
    }

/** [GitClient] of the server. */
@Suppress("TooManyFunctions") // one per Git operation of the protocol
class RemoteGit(private val client: RpcClient) : GitClient {
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

    override suspend fun commit(root: String, message: String, paths: List<String>) {
        client.call(Method.GitCommit) {
            put(ROOT, root)
            put("message", message)
            put("paths", paths.toJson())
        }
    }

    override suspend fun pull(root: String): String = client.call(Method.GitPull) { put(ROOT, root) }.string()

    override suspend fun push(root: String): String = client.call(Method.GitPush) { put(ROOT, root) }.string()

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

    override suspend fun blame(root: String, path: String): List<BlameLine> =
        client.call(Method.GitBlame) { file(root, path) }.blameLines()

    override suspend fun remoteBranches(root: String): List<String> =
        client.call(Method.GitRemoteBranches) { put(ROOT, root) }.strings()

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

/** [RecentProjectsStore] of the server. */
class RemoteRecentProjects(private val client: RpcClient) : RecentProjectsStore {
    override suspend fun load(): List<String> = client.call(Method.RecentLoad).strings()

    override suspend fun save(paths: List<String>) {
        client.call(Method.RecentSave) { put("paths", paths.toJson()) }
    }
}

/** [SessionStore] of the server. */
class RemoteSessions(private val client: RpcClient) : SessionStore {
    override suspend fun load(projectPath: String): ProjectSession? =
        client.call(Method.SessionLoad) { put("project", projectPath) }.session()

    override suspend fun save(projectPath: String, session: ProjectSession) {
        client.call(Method.SessionSave) {
            put("project", projectPath)
            put("session", session.toJson())
        }
    }
}

/**
 * Terminal running on the server. The request id of TerminalStart is the id of its snapshot stream, registered
 * before the request is sent so no screen can arrive before the client listens. Keys, pastes, resizes and scrolls
 * go through a queue so they reach the terminal in the order they were typed.
 */
class RemoteTerminalLauncher(private val client: RpcClient) : TerminalLauncher {
    override fun start(
        directory: String,
        columns: Int,
        rows: Int,
        cellWidthPx: Int,
        cellHeightPx: Int,
    ): TerminalProcess {
        val id = client.newId()
        val screens = client.openStream(id, StreamKind.Replacing)
        val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        client.scope.launch {
            try {
                client.call(Method.TerminalStart, id = id) {
                    put(DIRECTORY, directory)
                    size(columns, rows, cellWidthPx, cellHeightPx)
                }
                for (command in commands) command()
            } catch (_: IOException) {
                commands.close()
                client.closeStream(id)
            }
        }
        return object : TerminalProcess {
            override val output: Flow<TerminalSnapshot> =
                flow {
                    for (message in screens) {
                        if (message is StreamMessage.Event) emit(message.payload.field("snapshot").snapshot())
                    }
                }

            override fun write(bytes: ByteArray) {
                command(Method.TerminalWrite) { terminalInput(bytes) }
            }

            override fun sendKey(event: TerminalKeyEvent) {
                command(Method.TerminalKey) { put("event", event.toJson()) }
            }

            override fun paste(text: String) {
                command(Method.TerminalPaste) { put("text", text) }
            }

            override fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
                command(Method.TerminalResize) { size(columns, rows, cellWidthPx, cellHeightPx) }
            }

            override fun scroll(delta: Int) {
                command(Method.TerminalScroll) { put("delta", delta) }
            }

            override fun clear() {
                command(Method.TerminalClear)
            }

            override fun destroy() {
                commands.close()
                client.scope.launch { runCatching { client.call(Method.TerminalDestroy) { put(TERMINAL, id) } } }
                client.closeStream(id)
            }

            // Queued: commands reach the terminal in the order they were typed.
            private fun command(method: Method, params: JsonObjectBuilder.() -> Unit = {}) {
                commands.trySend {
                    client.call(method) {
                        put(TERMINAL, id)
                        params()
                    }
                }
            }
        }
    }
}

private fun JsonObjectBuilder.size(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
    put("columns", columns)
    put("rows", rows)
    put("cellWidthPx", cellWidthPx)
    put("cellHeightPx", cellHeightPx)
}
