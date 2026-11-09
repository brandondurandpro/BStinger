package org.example.kotlineditor.lsp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.example.kotlineditor.lsp.completion.DocumentCompletions
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.lsp.intelligence.CodeIntelligenceService
import org.example.kotlineditor.lsp.intelligence.IntelligenceSettings
import org.example.kotlineditor.lsp.intelligence.fileOperationParams
import org.example.kotlineditor.lsp.intelligence.filesMatching
import org.example.kotlineditor.lsp.memory.ProcessMemoryReader
import org.example.kotlineditor.lsp.memory.ProcessMemorySampler
import org.example.kotlineditor.lsp.provision.CandidateRegistry
import org.example.kotlineditor.lsp.supervisor.LanguageServerHooks
import org.example.kotlineditor.lsp.supervisor.LanguageServerSupervisor
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.lsp.supervisor.OrphanReaper
import org.example.kotlineditor.lsp.supervisor.SupervisorTimings
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.Languages
import org.example.kotlineditor.model.OperatedFile
import org.example.kotlineditor.model.ProjectCheckResult
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.telemetry.Telemetry
import java.io.Closeable
import java.nio.file.Path
import java.nio.file.Paths

/** Where the language servers keep their files, and how they are found and timed. */
class LanguageServerSettings(
    /** `~/.kotlin-editor` or the test's data directory: choices and the pids of running servers. */
    val dataDirectory: Path,
    /** Where pinned servers are downloaded, shared with ripgrep. */
    val toolsDirectory: Path = Paths.get(System.getProperty("user.home"), ".kotlin-editor", "tools"),
    /** The servers offered for each language, with their requirements checked on this machine. */
    val candidates: CandidateRegistry = CandidateRegistry(),
    /** Replaces the project check (tests: no `vue-tsc` to download and run). */
    val projectCheck: ((project: String) -> ProjectCheckResult)? = null,
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
    private val targets = LanguageServerTargets(settings)
    private val publisher: StatusPublisher = StatusPublisher(settings.statusHoldMs, sampler) { supervisor.servers() }
    private val lastFiles = HashMap<String, Pair<String, List<String>>>()
    private val documents = OpenDocuments()
    private val completions = DocumentCompletions(documents, settings.completionTimeoutMs, ::serversOf)

    /** Hover, navigation, usages, diagnostics, rename, formatting and code actions of the open documents. */
    val intelligence: CodeIntelligenceService =
        CodeIntelligenceService(documents, ::serversOf, settings.intelligence, ::serversOfSession, scope)
    private val supervisor =
        LanguageServerSupervisor(
            scope,
            sampler,
            OrphanReaper(settings.dataDirectory.resolve("lsp").resolve("pids")),
            settings.timings,
            onChange = publisher::changed,
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

    init {
        scope.launch { publisher.run() }
    }

    /**
     * Checks the whole [project] with `vue-tsc` (downloaded on first use): the problems of every file, open or not.
     * [ProjectCheckResult.status] is NotReady, with the reason in its summary, when the check could not run.
     */
    suspend fun checkProject(project: String): ProjectCheckResult =
        withContext(Dispatchers.IO) { (settings.projectCheck ?: ProjectChecker(targets.downloader)::check)(project) }

    /** The status once newer than [since], or the current one after [LanguageServerSettings.statusHoldMs]. */
    suspend fun status(since: Long): LanguageServersStatus = publisher.status(since)

    /**
     * Sets the files [session] has open in [project]: the servers they need run, and the languages that need a
     * server not chosen yet are returned. The servers of the project it had open before, if another, stop now when
     * no other session uses them; those of files closed in the same project stop after the idle delay.
     */
    fun use(session: String, project: String, files: List<String>): List<String> {
        val left = synchronized(lastFiles) { lastFiles.put(session, project to files) }?.first
        val (wanted, unchosen) = targets.targetsOf(project, files)
        supervisor.use(session, wanted)
        // A project left (another one opened, or none: an empty [project]) has no file to come back to soon.
        if (left != null && left != project) supervisor.discardInside(left)
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
        val place = { file: String -> Languages.languageOf(file)?.let { it to targets.rootOf(it, file, project) } }
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
    suspend fun complete(session: String, path: String, version: Int, line: Int, column: Int): CompletionResult =
        completions.complete(session, path, version, line, column)

    /** The documentation of item [index] of [session]'s completion answer [token]; see [DocumentCompletions]. */
    suspend fun resolveCompletion(session: String, token: String, index: Int): String? =
        completions.resolve(session, token, index)

    // Its own language's servers first (they answer by default), then those of the companion languages that hold it.
    private fun serversOf(document: MirroredDocument): List<ManagedServer> {
        val own = supervisor.serving(document.language, document.root)
        val companions = Languages.companionsOf(document.path)
        val beside = supervisor.servingPath(document.path).filter { it.language in companions }
        return own + beside
    }

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
        withContext(Dispatchers.IO) { targets.choice(language, project) }

    /** Saves [candidate] for [language] and moves every session to it: the old server stops now. */
    fun choose(language: String, project: String, candidate: String) {
        targets.choose(language, project, candidate)
        val sessions = synchronized(lastFiles) { lastFiles.toMap() }
        sessions.forEach { (session, files) ->
            val (wanted, _) = targets.targetsOf(files.first, files.second)
            supervisor.use(session, wanted)
        }
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
