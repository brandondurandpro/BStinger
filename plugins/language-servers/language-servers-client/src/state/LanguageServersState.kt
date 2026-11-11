package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.withContext
import org.example.kotlineditor.memory.ProcessMemory
import org.example.kotlineditor.model.CodeIntelligence
import org.example.kotlineditor.model.CompletionResult
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.LanguageServers
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.ProjectCheckResult
import java.io.IOException
import java.util.Locale

private const val RETRY_MS = 1_000L
private const val UI_MEMORY_SAMPLE_MS = 2_000L

// Declared when no project is open: the server then knows the project left.
private const val NO_PROJECT = ""
private const val BYTES_PER_MB = 1024.0 * 1024.0
private const val MB_PER_GB = 1024.0
private val STOPPED = setOf(LanguageServerState.Stopped, LanguageServerState.Crashed, LanguageServerState.Unavailable)

/** The files a session has open in a project, as declared to the server. */
private data class Declaration(val project: String, val files: List<String>, val round: Int = 0)

/** The log of a language server shown in the Language Servers window. */
class LanguageServerLog(
    /** Server it belongs to. */
    val id: String,
    /** Its lines, oldest first. */
    val lines: List<String>,
)

/**
 * The language servers as the IDE shows them (see #26): the status the server pushes, the files the open project
 * needs servers for, the choice of a server when a language has none yet, and the user's controls.
 */
@Stable
@Suppress("TooManyFunctions")
class LanguageServersState(
    private val servers: LanguageServers,
    parentScope: CoroutineScope,
    connections: StateFlow<Long> = MutableStateFlow(1L),
) {
    // Its own jobs, cancelled by close() when the IDE switches to another server.
    private val jobs = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = parentScope + jobs

    /** Last status received from the server. */
    var status by mutableStateOf(LanguageServersStatus.Empty)
        private set

    /**
     * The process of the UI. Replaced by the tests of a server in another process: theirs runs in the process of
     * the UI.
     */
    var uiProcess: UiProcess by mutableStateOf(UiProcess.current())

    // Last memory read of the process of the UI, null before the first and where it cannot be read.
    private var uiMemory: ProcessMemory? by mutableStateOf(null)

    /** Memory of the UI and of the IDE server, as last measured. */
    val memory: IdeMemory get() = ideMemory(uiProcess.identity, uiMemory, status.server)

    /** Whether a language server runs: before that, nothing can adapt the code to a file operation. */
    val anyRunning: Boolean get() = status.servers.any { it.state == LanguageServerState.Running }

    /** The choice offered (the banner above the editors), null when none. */
    var choice: LanguageServerChoice? by mutableStateOf(null)
        private set

    /** The choice dialog is open over the banner. */
    var choiceDialogOpen by mutableStateOf(false)
        private set

    /** The server selected in the choice dialog. */
    var selectedCandidate: String? by mutableStateOf(null)
        private set

    /** A choice is being saved. */
    var choosing by mutableStateOf(false)
        private set

    /** Last failure of a control, shown in the Language Servers window. */
    var error: String? by mutableStateOf(null)
        private set

    /** The problems of the open documents, as the server last sent them. */
    var diagnostics by mutableStateOf(DiagnosticsSnapshot.Empty)
        private set

    /** The requests of hover, navigation, usages, problems and edits. */
    val intelligence: CodeIntelligence get() = servers

    /** The log open in the Language Servers window. */
    var log: LanguageServerLog? by mutableStateOf(null)
        private set

    /** The last check of the whole project (`vue-tsc`), null before the first. */
    var projectCheck: ProjectCheckResult? by mutableStateOf(null)
        private set

    /** Whether a check of the whole project is running. */
    var checking by mutableStateOf(false)
        private set

    // "Not now" for these languages, until the IDE restarts.
    private val dismissed = mutableSetOf<String>()
    private val declarations = MutableStateFlow<Declaration?>(null)
    private var declared: Declaration? = null
    private var logJob: Job? = null
    private val documents = DocumentSync(servers, scope)

    init {
        scope.launch { poll() }
        scope.launch { pollDiagnostics() }
        scope.launch { sampleUiMemory() }
        // One at a time, in order: each declaration replaces the previous one on the server.
        scope.launch { declarations.filterNotNull().collect(::send) }
        // A restarted server lost the sessions and what they declared: declared again at each reconnection.
        val connected = connections.value
        scope.launch {
            connections.filter { it > connected }.collect {
                declarations.value?.let { declarations.value = it.copy(round = it.round + 1) }
                documents.resend()
            }
        }
    }

    /** The servers of the project at [projectRoot] (their root is inside it), in a stable order. */
    fun serversOf(projectRoot: String): List<LanguageServerInfo> =
        status.servers.filter { server -> isInside(server.root, projectRoot) }

    /** The open [project] has [files] open (null project: none is open). */
    fun declare(project: String?, files: List<String>) {
        val previous = declarations.value
        // No project: the one left stops its servers now, like a switch to another project.
        declarations.value =
            when {
                project != null -> Declaration(project, files.distinct().sorted())
                previous != null -> Declaration(NO_PROJECT, emptyList())
                else -> return
            }
    }

    /** The open [project] shows [documents], by path: their text is mirrored for the language servers. */
    fun syncDocuments(project: String?, documents: Map<String, Document>) = this.documents.track(project, documents)

    /**
     * What the language server of [path] proposes at the caret of [document]; [CompletionResult.NotReady] when
     * it has none, or none ready.
     */
    suspend fun complete(path: String, document: Document): CompletionResult {
        val version = documents.versionOf(path, document) ?: return CompletionResult.NotReady
        val caret = document.caret
        return try {
            servers.complete(path, version, caret.line, caret.column)
        } catch (_: IOException) {
            CompletionResult.NotReady
        }
    }

    /** The documentation of item [index] of the completion answer [token]; null when none, or unreachable. */
    suspend fun resolveCompletion(token: String, index: Int): String? =
        try {
            servers.resolveCompletion(token, index)
        } catch (_: IOException) {
            null
        }

    /** Version of [path] the server has once the edits made so far are sent; null when it is not mirrored. */
    fun versionOf(path: String, document: Document): Int? = documents.versionOf(path, document)

    /** Uses [candidate] for the language of the choice panel. */
    fun choose(candidate: String) {
        val shown = choice ?: return
        val project = declared?.project ?: return
        choosing = true
        scope.launch {
            try {
                servers.choose(shown.language, project, candidate)
                choice = null
                choiceDialogOpen = false
                declared?.let { send(it) }
            } catch (failure: IOException) {
                error = "Could not save the choice: ${failure.message}"
            } finally {
                choosing = false
            }
        }
    }

    /**
     * The servers offered for [language], for the Settings dialog: on the welcome screen as in a project, whose
     * own choice would apply.
     */
    suspend fun choiceFor(language: String): LanguageServerChoice =
        servers.choice(language, declared?.project.orEmpty())

    /**
     * Uses [candidate] for [language] from the Settings dialog, as the choice dialog does: the running servers
     * switch to it, and the banner asking for a server of [language] goes away. Throws when the server is out of
     * reach.
     */
    suspend fun use(language: String, candidate: String) {
        servers.choose(language, declared?.project.orEmpty(), candidate)
        if (choice?.language == language) {
            choice = null
            choiceDialogOpen = false
        }
        declared?.let { send(it) }
    }

    /** Opens the choice dialog for [language] again, to change the server. */
    fun offerChoice(language: String, project: String) =
        control {
            val offered = servers.choice(language, project).takeIf { it.candidates.isNotEmpty() }
            choice = offered
            if (offered != null) openChoiceDialog()
        }

    /** Opens the choice dialog on the current server, else the recommended one. */
    fun openChoiceDialog() {
        val shown = choice ?: return
        selectedCandidate = shown.chosen ?: recommendedOf(shown)?.id ?: shown.candidates.first().id
        choiceDialogOpen = true
    }

    /** Selects [candidate] in the choice dialog. */
    fun select(candidate: String) {
        selectedCandidate = candidate
    }

    /** Selects the next ([delta] 1) or previous (-1) server of the dialog, wrapping around. */
    fun moveSelection(delta: Int) {
        val candidates = choice?.candidates ?: return
        val index = candidates.indexOfFirst { it.id == selectedCandidate }
        selectedCandidate = candidates[(index + delta).mod(candidates.size)].id
    }

    /** Closes the choice dialog; the banner stays. */
    fun closeChoiceDialog() {
        choiceDialogOpen = false
    }

    /** Closes the choice without choosing: no server for that language until the IDE restarts. */
    fun notNow() {
        choice?.let { dismissed += it.language }
        choice = null
        choiceDialogOpen = false
    }

    /** Stops polling and controlling this server: the IDE switched to another one. */
    fun close() {
        documents.close()
        jobs.cancel()
    }

    /** Restarts [server] (once, even when clicked twice before the status changes). */
    fun restart(server: LanguageServerInfo) = control { servers.restart(server.id, server.generation) }

    /** Stops [server]. */
    fun stop(server: LanguageServerInfo) = control { servers.stop(server.id) }

    /** Starts [server] after a stop or a crash. */
    fun start(server: LanguageServerInfo) = control { servers.start(server.id) }

    /** Shows the log of [server], refreshed every second while shown, or hides it when it is already shown. */
    fun toggleLog(server: LanguageServerInfo) {
        logJob?.cancel()
        if (log?.id == server.id) {
            log = null
            return
        }
        log = LanguageServerLog(server.id, emptyList())
        logJob =
            scope.launch {
                while (true) {
                    try {
                        val lines = servers.log(server.id)
                        if (log?.id == server.id && lines != log?.lines) log = LanguageServerLog(server.id, lines)
                    } catch (failure: IOException) {
                        error = failure.message
                    }
                    delay(RETRY_MS)
                }
            }
    }

    /** Whether [server] can be restarted, stopped or started now. */
    fun isRunning(server: LanguageServerInfo) = server.state !in STOPPED

    /** Checks the whole [project] with its type checker; the result replaces the previous one. */
    fun checkProject(project: String) {
        if (checking) return
        checking = true
        error = null
        scope.launch {
            try {
                projectCheck = servers.checkProject(project)
            } catch (failure: IOException) {
                error = "Could not check the project: ${failure.message}"
            } finally {
                checking = false
            }
        }
    }

    private fun control(action: suspend () -> Unit) {
        error = null
        scope.launch {
            try {
                action()
            } catch (failure: IOException) {
                error = failure.message
            }
        }
    }

    private suspend fun send(declaration: Declaration) {
        declared = declaration
        try {
            val unchosen = servers.use(declaration.project, declaration.files)
            val language = unchosen.firstOrNull { it !in dismissed }
            if (language != null && choice?.language != language) {
                val offered = servers.choice(language, declaration.project)
                choice = offered.takeIf { it.chosen == null && it.candidates.isNotEmpty() }
            }
        } catch (failure: IOException) {
            error = "Language servers: ${failure.message}"
        }
    }

    // Long poll: each answer is newer than the version we have (or the current one after a while).
    @Suppress("TooGenericExceptionCaught")
    private suspend fun poll() {
        var since = 0L
        while (true) {
            try {
                val next = servers.status(since)
                status = next
                since = next.version
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(RETRY_MS)
            }
        }
    }

    // Reads the memory of the UI every few seconds, away from the UI thread (and from the virtual time of the tests).
    // A read that would show the same text is dropped: an idle IDE draws no frame for it.
    private suspend fun sampleUiMemory() {
        while (true) {
            val process = uiProcess
            val read = withContext(Dispatchers.Default) { process.memory() }
            val shown = uiMemory?.residentBytes?.let(::memoryText)
            val next = read?.residentBytes?.let(::memoryText)
            if (next != shown) uiMemory = read
            withContext(Dispatchers.Default) { delay(UI_MEMORY_SAMPLE_MS) }
        }
    }

    // Long poll, like the status: a server that restarted answers its own (lower) version at once.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun pollDiagnostics() {
        var since = 0L
        while (true) {
            try {
                val next = servers.diagnostics(since)
                diagnostics = next
                since = next.version
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                delay(RETRY_MS)
            }
        }
    }

    private fun isInside(root: String, project: String): Boolean {
        val separator = if (project.contains('\\') && !project.contains('/')) "\\" else "/"
        return root == project || root.startsWith(project.trimEnd('/', '\\') + separator)
    }
}

/** A byte count for the status bar: `312 MB`, `1.4 GB`, written the same in every locale. */
fun memoryText(bytes: Long): String {
    val mb = bytes / BYTES_PER_MB
    return if (mb >= MB_PER_GB) "%.1f GB".format(Locale.ROOT, mb / MB_PER_GB) else "${mb.toLong()} MB"
}

/** The server offered first: the first one that can run on the server machine. */
fun recommendedOf(choice: LanguageServerChoice): LanguageServerCandidateInfo? =
    choice.candidates.firstOrNull { it.available }
