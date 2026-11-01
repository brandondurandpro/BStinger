package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import org.example.kotlineditor.model.ConnectionStatus
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.LanguageServers
import org.example.kotlineditor.model.LanguageServersStatus
import java.io.IOException

private const val RETRY_MS = 1_000L
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
    connection: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Connected),
) {
    // Its own jobs, cancelled by close() when the IDE switches to another server.
    private val jobs = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = parentScope + jobs

    /** Last status received from the server. */
    var status by mutableStateOf(LanguageServersStatus.Empty)
        private set

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

    /** The log open in the Language Servers window. */
    var log: LanguageServerLog? by mutableStateOf(null)
        private set

    // "Not now" for these languages, until the IDE restarts.
    private val dismissed = mutableSetOf<String>()
    private val declarations = MutableStateFlow<Declaration?>(null)
    private var declared: Declaration? = null
    private var logJob: Job? = null

    init {
        scope.launch { poll() }
        // One at a time, in order: each declaration replaces the previous one on the server.
        scope.launch { declarations.filterNotNull().collect(::send) }
        // A restarted server lost the sessions and what they declared: declared again at each reconnection.
        scope.launch {
            connection.drop(1).filter { it == ConnectionStatus.Connected }.collect {
                declarations.value?.let { declarations.value = it.copy(round = it.round + 1) }
            }
        }
    }

    /** The servers of the project at [projectRoot] (their root is inside it), in a stable order. */
    fun serversOf(projectRoot: String): List<LanguageServerInfo> =
        status.servers.filter { server -> isInside(server.root, projectRoot) }

    /** The open [project] has [files] open (null project: none is open). */
    fun declare(project: String?, files: List<String>) {
        val previous = declarations.value
        declarations.value =
            when {
                project != null -> Declaration(project, files.distinct().sorted())
                previous != null -> Declaration(previous.project, emptyList())
                else -> return
            }
    }

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

    private fun isInside(root: String, project: String): Boolean {
        val separator = if (project.contains('\\') && !project.contains('/')) "\\" else "/"
        return root == project || root.startsWith(project.trimEnd('/', '\\') + separator)
    }
}

/** A byte count for the status bar: `312 MB`, `1.4 GB`. */
fun memoryText(bytes: Long): String {
    val mb = bytes / BYTES_PER_MB
    return if (mb >= MB_PER_GB) "%.1f GB".format(mb / MB_PER_GB) else "${mb.toLong()} MB"
}

/** The server offered first: the first one that can run on the server machine. */
fun recommendedOf(choice: LanguageServerChoice): LanguageServerCandidateInfo? =
    choice.candidates.firstOrNull { it.available }
