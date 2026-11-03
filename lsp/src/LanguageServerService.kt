package org.example.kotlineditor.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.completion.CompletionAnswers
import org.example.kotlineditor.lsp.completion.OfferedCompletions
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.lsp.intelligence.CodeIntelligenceService
import org.example.kotlineditor.lsp.intelligence.IntelligenceSettings
import org.example.kotlineditor.lsp.intelligence.fileOperationParams
import org.example.kotlineditor.lsp.intelligence.filesMatching
import org.example.kotlineditor.lsp.jsonrpc.LspConnectionClosedException
import org.example.kotlineditor.lsp.jsonrpc.LspResponseException
import org.example.kotlineditor.lsp.memory.ProcessMemoryReader
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.lsp.provision.CandidateStatus
import org.example.kotlineditor.lsp.provision.LanguageServerChoiceStore
import org.example.kotlineditor.lsp.provision.ToolDownloader
import org.example.kotlineditor.lsp.supervisor.LanguageServerHooks
import org.example.kotlineditor.lsp.supervisor.LanguageServerSpec
import org.example.kotlineditor.lsp.supervisor.LanguageServerSupervisor
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.lsp.supervisor.OrphanReaper
import org.example.kotlineditor.lsp.supervisor.ServerTarget
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.OperatedFile
import org.example.kotlineditor.model.ServerMemory
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.telemetry.Telemetry
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/** Where the language servers keep their files, and how they are found and timed. */
class LanguageServerSettings(
    /** `~/.kotlin-editor` or the test's data directory: choices and the pids of running servers. */
    val dataDirectory: Path,
    /** Where pinned servers are downloaded, shared with ripgrep. */
    val toolsDirectory: Path = Paths.get(System.getProperty("user.home"), ".kotlin-editor", "tools"),
    /** The servers offered for each language, with their requirements checked on this machine. */
    val candidates: CandidateRegistry = CandidateRegistry(),
    /** Delays of the supervisor. */
    val timings: SupervisorTimings = SupervisorTimings(),
    /** How long a status request waits for a change before answering the current status. */
    val statusHoldMs: Long = 20_000,
    /** Milliseconds between two memory samples of a server. */
    val sampleIntervalMs: Long = 2_000,
    /** How long a completion waits for the edits it follows, then for the language server's answer. */
    val completionTimeoutMs: Long = 10_000,
    /** Delays and limits of hover, navigation, usages, diagnostics... */
    val intelligence: IntelligenceSettings = IntelligenceSettings(),
)

/**
 * The language servers of the IDE server, as its requests use them (see #26): which ones the open files need,
 * their status as versioned snapshots, the user's controls (restart, stop, start, log) and the choice of a
 * server for a language.
 */
@Suppress("TooManyFunctions")
class LanguageServerService(
    private val settings: LanguageServerSettings,
    /** What measures the starts of the servers, when the user agreed. */
    private val telemetry: () -> Telemetry = { Telemetry.Disabled },
) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val reader = ProcessMemoryReader.forCurrentOs()
    private val sampler = ProcessMemorySampler(reader, System::currentTimeMillis, scope, settings.sampleIntervalMs)
    private val choices = LanguageServerChoiceStore(settings.dataDirectory.resolve("language-servers.properties"))
    private val downloader = ToolDownloader(settings.toolsDirectory)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val published = MutableStateFlow(LanguageServersStatus.Empty.copy(version = 1))
    private val lastFiles = HashMap<String, Pair<String, List<String>>>()
    private val specs = ConcurrentHashMap<String, LanguageServerSpec>()
    private val documents = OpenDocuments()
    private val completions = OfferedCompletions(settings.completionTimeoutMs)

    /** Hover, navigation, usages, diagnostics, rename, formatting and code actions of the open documents. */
    val intelligence: CodeIntelligenceService =
        CodeIntelligenceService(documents, ::serversOf, settings.intelligence, ::serversOfSession, scope)
    private val supervisor =
        LanguageServerSupervisor(
            scope,
            sampler,
            OrphanReaper(settings.dataDirectory.resolve("lsp").resolve("pids")),
            settings.timings,
            onChange = { changes.trySend(Unit) },
            documents = documents,
            hooks =
            LanguageServerHooks(
                onDiagnostics = intelligence::published,
                onApplyEdit = intelligence::applyEdit,
                onRestarted = intelligence::restarted,
                onDiagnosticsRefresh = intelligence::diagnosticsRefresh,
                telemetry = telemetry,
            ),
        )

    // When the last status was published, in milliseconds since the epoch.
    private var publishedAt = 0L

    init {
        scope.launch { publish() }
    }

    /** The status once newer than [since], or the current one after [LanguageServerSettings.statusHoldMs]. */
    suspend fun status(since: Long): LanguageServersStatus {
        // Older than ours, or higher (known from a previous IDE server): the current status at once.
        if (since != published.value.version) return published.value
        return withTimeoutOrNull(settings.statusHoldMs) { published.first { it.version > since } } ?: published.value
    }

    /**
     * Sets the files [session] has open in [project]: the servers they need run, and the languages that need a
     * server not chosen yet are returned.
     */
    fun use(session: String, project: String, files: List<String>): List<String> {
        synchronized(lastFiles) { lastFiles[session] = project to files }
        val (targets, unchosen) = targetsOf(project, files)
        supervisor.use(session, targets)
        return unchosen
    }

    /** [session] ended. */
    fun release(session: String) {
        synchronized(lastFiles) { lastFiles.remove(session) }
        documents.release(session) { document -> closed(document) }
        completions.release(session)
        intelligence.released(session)
        supervisor.release(session)
    }

    /**
     * [session] opened [path] of [project] with [text] at [version], or sends it again. Its language server gets it
     * now if it runs, else when it starts. Files no language server handles are ignored.
     */
    fun openDocument(session: String, project: String, path: String, version: Int, text: String) {
        val place = { file: String -> Languages.languageOf(file)?.let { it to rootOf(it, file, project) } }
        documents.open(session, path, version, text, place) { document, reopened ->
            serversOf(document).forEach { server ->
                if (reopened) server.didClose(document)
                server.didOpen(document)
            }
        }
        documents.get(path)?.let(intelligence::changed)
    }

    /** Applies the edits of [session] to [path]; throws [org.example.kotlineditor.lsp.documents.OutOfSyncException]. */
    fun changeDocument(session: String, path: String, version: Int, changes: List<TextChange>) {
        if (Languages.languageOf(path) == null) return
        documents.change(session, path, version, changes) { document ->
            serversOf(document).forEach { it.didChange(document, changes) }
        }
        // Outside the lock of the documents.
        documents.get(path)?.let(intelligence::changed)
    }

    /** [session] closed [path]. */
    fun closeDocument(session: String, path: String) {
        documents.close(session, path, ::closed)
    }

    private fun closed(document: MirroredDocument) {
        serversOf(document).forEach { it.didClose(document) }
        intelligence.closed(document)
    }

    /**
     * Completion at [line], [column] of [path], once [session]'s [version] of it arrived: what its language server
     * proposes, filtered by the word before the caret and capped. [CompletionResult.NotReady] when no server can
     * answer (none for this file, starting, crashed, restarted during the request).
     */
    suspend fun complete(session: String, path: String, version: Int, line: Int, column: Int): CompletionResult {
        val document = documents.get(path)
        val hasValidClientVersion =
            document != null &&
                run {
                    val matchingVersion =
                        withTimeoutOrNull(settings.completionTimeoutMs) {
                            document.clientVersion.firstOrNull { it.session == session && it.version >= version }
                        }
                    matchingVersion != null
                }
        return if (document != null && hasValidClientVersion) {
            ask(session, document, line, column)
        } else {
            CompletionResult.NotReady
        }
    }

    /** The documentation of item [index] of [session]'s completion answer [token]; see [OfferedCompletions]. */
    suspend fun resolveCompletion(session: String, token: String, index: Int): String? =
        completions.resolve(session, token, index)

    private suspend fun ask(session: String, document: MirroredDocument, line: Int, column: Int): CompletionResult {
        val text = documents.locked { document.line(line) }
        val params =
            buildJsonObject {
                putJsonObject("textDocument") { put("uri", document.uri) }
                putJsonObject("position") {
                    put("line", line)
                    put("character", column)
                }
            }
        return try {
            // While another server takes over the language, the old one, stopping, is not ready any more.
            val answered =
                withTimeoutOrNull(settings.completionTimeoutMs) {
                    serversOf(document).firstNotNullOfOrNull { server ->
                        server.request("textDocument/completion", params)?.let { server to it }
                    }
                }
            answered?.let { (server, answer) ->
                val (result, items) = CompletionAnswers.translateWithItems(answer, text, column)
                result.copy(token = completions.offer(session, server, items))
            } ?: CompletionResult.NotReady
        } catch (_: LspConnectionClosedException) {
            CompletionResult.NotReady
        } catch (_: LspResponseException) {
            // The server refused (content modified, request failed): nothing to propose this time.
            CompletionResult(column, emptyList(), incomplete = true, ready = true)
        }
    }

    private fun serversOf(document: MirroredDocument): List<ManagedServer> =
        supervisor.serving(document.language, document.root)

    /**
     * Files changed on disk through the IDE ([DiskChange] by absolute path): the servers whose root holds them are
     * told (`workspace/didChangeWatchedFiles`), and an open document that was written is saved (`didSave`).
     */
    fun diskChanged(changes: List<Pair<String, DiskChange>>) {
        // One notification per server, with all its files.
        val byServer = LinkedHashMap<ManagedServer, MutableList<Pair<String, Int>>>()
        for ((path, change) in changes) {
            val uri = Paths.get(path).toUri().toString()
            supervisor.servingPath(path).forEach { byServer.getOrPut(it) { ArrayList() } += uri to change.type }
        }
        byServer.forEach { (server, files) -> server.filesChanged(files) }
        val written = changes.filter { it.second == DiskChange.Changed }.mapNotNull { documents.get(it.first) }
        written.forEach { saved -> documents.locked { serversOf(saved).forEach { it.didSave(saved) } } }
    }

    /**
     * [files] were created, renamed or deleted through the IDE ([operation]): the servers whose root holds them and
     * whose `fileOperations.did<Operation>` filters match are told (`workspace/didRenameFiles`...).
     */
    fun filesOperated(operation: FileOperation, files: List<OperatedFile>) {
        val name = "did${operation.lspName}"
        val servers = files.flatMap { supervisor.servingPath(it.path) }.distinct()
        for (server in servers) {
            val concerned = filesMatching(server.capability("workspace"), name, files)
            if (concerned.isNotEmpty()) server.filesOperated(operation, fileOperationParams(operation, concerned))
        }
    }

    // The servers of the project [session] has open.
    private fun serversOfSession(session: String): List<ManagedServer> {
        val project = synchronized(lastFiles) { lastFiles[session]?.first } ?: return emptyList()
        return supervisor.servingInside(project)
    }

    /** See [LanguageServerSupervisor.restart]. */
    fun restart(id: String, generation: Long) = supervisor.restart(id, generation)

    /** See [LanguageServerSupervisor.stop]. */
    fun stop(id: String) = supervisor.stop(id)

    /** See [LanguageServerSupervisor.start]. */
    fun start(id: String) = supervisor.start(id)

    /** See [LanguageServerSupervisor.log]. */
    fun log(id: String): List<String> = supervisor.log(id)

    /** The servers offered for [language], their requirements checked now, and the chosen one. Blocking. */
    suspend fun choice(language: String, project: String): LanguageServerChoice =
        withContext(Dispatchers.IO) {
            val offered = settings.candidates.candidates(language).map(::infoOf)
            LanguageServerChoice(language, Languages.nameOf(language), offered, choices.choice(language, project))
        }

    private fun infoOf(status: CandidateStatus) =
        LanguageServerCandidateInfo(
            id = status.candidate.id,
            displayName = status.candidate.displayName,
            licence = status.candidate.licence,
            strengths = status.candidate.strengths,
            drawbacks = status.candidate.drawbacks,
            available = status.available,
            details = status.details,
        )

    /** Saves [candidate] for [language] and moves every session to it: the old server stops now. */
    fun choose(language: String, project: String, candidate: String) {
        require(settings.candidates.offered(language).any { it.id == candidate }) {
            "Unknown language server $candidate for $language"
        }
        choices.choose(language, candidate)
        choices.clearProjectChoice(language, project)
        val sessions = synchronized(lastFiles) { lastFiles.toMap() }
        sessions.forEach { (session, files) -> supervisor.use(session, targetsOf(files.first, files.second).first) }
        supervisor.discard(language, keep = candidate)
    }

    /** The pid of a server's process (tests). */
    fun pidOf(id: String): Long? = supervisor.pidOf(id)

    /** Stops every language server. */
    override fun close() {
        supervisor.close()
        sampler.close()
        reader.close()
        scope.cancel()
    }

    private fun targetsOf(project: String, files: List<String>): Pair<List<ServerTarget>, List<String>> {
        val targets = LinkedHashMap<String, ServerTarget>()
        val unchosen = LinkedHashSet<String>()
        // No runtime detection here (it runs programs): it happens when the server starts.
        val handled = files.mapNotNull { file -> Languages.languageOf(file)?.let { file to it } }
        for ((file, language) in handled.filter { settings.candidates.offered(it.second).isNotEmpty() }) {
            val offered = settings.candidates.offered(language)
            val chosen = choices.choice(language, project)?.let { id -> offered.firstOrNull { it.id == id } }
            if (chosen == null) {
                unchosen += language
            } else {
                val spec = specs.getOrPut(chosen.id) { specOf(language, chosen.id) }
                val target = ServerTarget(spec, rootOf(language, file, project))
                targets[target.id] = target
            }
        }
        return targets.values.toList() to unchosen.toList()
    }

    // The requirements are checked again at each start: PHP may have been installed since.
    private fun specOf(language: String, id: String): LanguageServerSpec {
        val candidate = settings.candidates.offered(language).firstOrNull { it.id == id }
        val options = candidate?.initializationOptions
        return LanguageServerSpec(id, language, options, candidate?.withoutInlayHints) {
            val status = settings.candidates.candidates(language).first { it.candidate.id == id }
            settings.candidates.commandFor(status, downloader)
        }
    }

    // PHP: the closest composer.json between the file and the project, the project otherwise. A root that is the
    // project is named as the client names it: the status bar matches servers to projects by their root.
    private fun rootOf(language: String, file: String, project: String): String {
        val marker = Languages.rootMarkerOf(language) ?: return project
        val top = Paths.get(project).toAbsolutePath().normalize()
        var directory = absolute(file).parent
        while (directory != null && isSearched(directory, top, marker)) {
            directory = directory.parent
        }
        val found = directory?.takeIf { it.startsWith(top) && it != top }
        return found?.toString() ?: project
    }

    private suspend fun publish() {
        while (scope.isActive) {
            withTimeoutOrNull(PUBLISH_TICK_MS) { changes.receive() }
            val last = published.value
            val servers = supervisor.servers()
            val self = sampler.sampleSelf()
            val runtime = Runtime.getRuntime()
            val heapUsed = runtime.totalMemory() - runtime.freeMemory()
            val server = ServerMemory(heapUsed, runtime.maxMemory(), self.residentBytes)
            val serversChanged = servers.map(::withoutMemory) != last.servers.map(::withoutMemory)
            val serverMemoryMoved =
                servers.any { now -> moved(privateOf(last, now.id), now.memory?.privateBytes) }
            val residentMoved = moved(last.server.residentBytes, server.residentBytes)
            val heapMoved = moved(last.server.heapUsedBytes, server.heapUsedBytes)
            val ownMemoryMoved = residentMoved || heapMoved
            val memoryMoved = serverMemoryMoved || ownMemoryMoved
            val now = System.currentTimeMillis()
            if (shouldPublish(last, servers, serversChanged, memoryMoved, now)) {
                published.value = LanguageServersStatus(last.version + 1, server, servers)
                publishedAt = now
            }
        }
    }

    // A server that starts, stops or first reports its memory is shown at once. A memory that only moved waits for
    // MEMORY_PUBLISH_MS after the last status: the IDE server's heap moves all the time when it shares the JVM of the
    // UI, which would otherwise send a status every second to an idle IDE.
    private fun shouldPublish(
        last: LanguageServersStatus,
        servers: List<LanguageServerInfo>,
        serversChanged: Boolean,
        memoryMoved: Boolean,
        now: Long,
    ): Boolean {
        val memoryAppeared = servers.any { info -> privateOf(last, info.id) == null && info.memory != null }
        val shownAtOnce = serversChanged || memoryAppeared
        val memoryDue = now - publishedAt >= MEMORY_PUBLISH_MS
        val memoryShown = memoryMoved && memoryDue
        return shownAtOnce || memoryShown
    }

    private fun privateOf(status: LanguageServersStatus, id: String): Long? {
        val server = status.servers.firstOrNull { it.id == id }
        return server?.memory?.privateBytes
    }

    // Still inside the project, and without the marker: the search goes on to the parent directory.
    private fun isSearched(directory: Path, top: Path, marker: String): Boolean {
        val insideProject = directory.startsWith(top)
        val hasMarker = Files.isRegularFile(directory.resolve(marker))
        return insideProject && !hasMarker
    }

    private fun absolute(path: String): Path {
        val absolute = Paths.get(path).toAbsolutePath()
        return absolute.normalize()
    }

    private fun withoutMemory(info: LanguageServerInfo) = info.copy(memory = null, peakPrivateBytes = 0)

    // A change worth sending: more than 5 MB and more than 2 %, so that the status bar does not flicker.
    private fun moved(before: Long?, after: Long?): Boolean {
        if (before == null || after == null) return before != after
        return abs(after - before) > maxOf(MIN_MOVE_BYTES, (before * MIN_MOVE_RATIO).toLong())
    }

    private companion object {
        const val PUBLISH_TICK_MS = 1_000L
        const val MEMORY_PUBLISH_MS = 5_000L
        const val MIN_MOVE_BYTES = 5L * 1024 * 1024
        const val MIN_MOVE_RATIO = 0.02
    }
}

/** What happened to a file on disk, as the language servers are told (LSP FileChangeType). */
enum class DiskChange(
    /** The FileChangeType value. */
    val type: Int,
) {
    /** It was created. */
    Created(1),

    /** Its content changed (written, saved). */
    Changed(2),

    /** It was deleted. */
    Deleted(DELETED_TYPE),
}

private const val DELETED_TYPE = 3
