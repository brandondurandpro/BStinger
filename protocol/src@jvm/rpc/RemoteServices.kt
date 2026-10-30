package org.example.kotlineditor.rpc

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.model.DirectoryPicker
import org.example.kotlineditor.model.FileContent
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.GitClient
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.ProjectFileSystem
import org.example.kotlineditor.model.ProjectSearch
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.RecentProjectsStore
import org.example.kotlineditor.model.SessionStore
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalLauncher
import org.example.kotlineditor.model.TerminalProcess
import org.example.kotlineditor.model.TerminalSnapshot
import org.example.kotlineditor.model.TextMatch
import java.io.IOException

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
        client.call(Method.ResolveDirectory) { string(path) }.entryOrNull()

    override suspend fun list(directory: FileEntry): List<FileEntry> =
        client.call(Method.ListDirectory) { entry(directory) }.entries()

    override suspend fun read(file: FileEntry): FileContent = client.call(Method.Read) { entry(file) }.content()

    override suspend fun fileAt(path: String): FileEntry? = client.call(Method.FileAt) { string(path) }.entryOrNull()

    override suspend fun write(file: FileEntry, text: String) {
        client.call(Method.Write) { entry(file).string(text) }
    }

    override suspend fun createFile(directory: FileEntry, name: String): FileEntry =
        client.call(Method.CreateFile) { entry(directory).string(name) }.entry()

    override suspend fun createDirectory(directory: FileEntry, name: String): FileEntry =
        client.call(Method.CreateDirectory) { entry(directory).string(name) }.entry()

    override suspend fun rename(entry: FileEntry, newName: String): FileEntry =
        client.call(Method.Rename) { entry(entry).string(newName) }.entry()

    override suspend fun delete(entry: FileEntry) {
        client.call(Method.Delete) { entry(entry) }
    }
}

/** [ProjectSearch] of the server: only the results travel. */
class RemoteSearch(private val client: RpcClient) : ProjectSearch {
    override suspend fun walkFiles(root: FileEntry, limit: Int): List<FileEntry> =
        client.call(Method.WalkFiles) { entry(root).int(limit) }.entries()

    override suspend fun findInFiles(root: FileEntry, query: String, ignoreCase: Boolean, limit: Int): List<TextMatch> =
        client.call(Method.FindInFiles) { entry(root).string(query).bool(ignoreCase).int(limit) }.matches()

    override suspend fun symbols(root: FileEntry): List<FileSymbol> =
        client.call(Method.Symbols) { entry(root) }.symbols()
}

/** [GitClient] of the server. */
class RemoteGit(private val client: RpcClient) : GitClient {
    override suspend fun status(root: String): Map<String, GitFileStatus> =
        client.call(Method.GitStatus) { string(root) }.gitStatus()

    override suspend fun branch(root: String): String? = client.call(Method.GitBranch) { string(root) }.stringOrNull()

    override suspend fun diff(root: String, path: String): List<DiffLine> =
        client.call(Method.GitDiff) { string(root).string(path) }.diffLines()
}

/** [RecentProjectsStore] of the server. */
class RemoteRecentProjects(private val client: RpcClient) : RecentProjectsStore {
    override suspend fun load(): List<String> = client.call(Method.RecentLoad).strings()

    override suspend fun save(paths: List<String>) {
        client.call(Method.RecentSave) { strings(paths) }
    }
}

/** [SessionStore] of the server. */
class RemoteSessions(private val client: RpcClient) : SessionStore {
    override suspend fun load(projectPath: String): ProjectSession? =
        client.call(Method.SessionLoad) { string(projectPath) }.session()

    override suspend fun save(projectPath: String, session: ProjectSession) {
        client.call(Method.SessionSave) { string(projectPath).session(session) }
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
        val screens = client.openStream(id)
        val commands = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        client.scope.launch {
            try {
                client.call(Method.TerminalStart, id = id) {
                    string(directory)
                        .int(columns)
                        .int(rows)
                        .int(cellWidthPx)
                        .int(cellHeightPx)
                }
                for (command in commands) command()
            } catch (_: IOException) {
                commands.close()
                client.closeStream(id)
            }
        }
        return object : TerminalProcess {
            override val output: Flow<TerminalSnapshot> = flow { for (snapshot in screens) emit(snapshot) }

            override fun write(bytes: ByteArray) {
                commands.trySend { client.call(Method.TerminalWrite) { long(id).bytes(bytes) } }
            }

            override fun sendKey(event: TerminalKeyEvent) {
                commands.trySend { client.call(Method.TerminalKey) { long(id).keyEvent(event) } }
            }

            override fun paste(text: String) {
                commands.trySend { client.call(Method.TerminalPaste) { long(id).string(text) } }
            }

            override fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
                commands.trySend {
                    client.call(Method.TerminalResize) {
                        long(id)
                            .int(columns)
                            .int(rows)
                            .int(cellWidthPx)
                            .int(cellHeightPx)
                    }
                }
            }

            override fun scroll(delta: Int) {
                commands.trySend { client.call(Method.TerminalScroll) { long(id).int(delta) } }
            }

            override fun destroy() {
                commands.close()
                client.scope.launch { runCatching { client.call(Method.TerminalDestroy) { long(id) } } }
                client.closeStream(id)
            }
        }
    }
}
