package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.ProjectRunner
import org.example.kotlineditor.model.RunChunk
import org.example.kotlineditor.model.RunStream
import java.util.UUID

// Lines kept in the output: older ones are dropped, the pane says how many.
private const val MAX_LINES = 50_000

private const val NO_RUNNER = "This server cannot run commands"
private const val EMPTY_COMMAND = "The command line is empty"

/** How a run ended, or that it did not. */
enum class RunStatus {
    /** The command is running. */
    Running,

    /** The command ended with exit code 0. */
    Succeeded,

    /** The command ended with another exit code. */
    Failed,

    /** The user stopped the command. */
    Stopped,

    /** The command could not start (program not found, directory gone). */
    NotStarted,

    /** The server stopped sending: it restarted, and the command with it. */
    Lost,
}

/** What the user selects in the tabs of the Run tool window. */
enum class RunTab {
    /** The output of the command. */
    Output,

    /** The tree of test results. */
    Tests,
}

/** One run of a configuration: its output, its status and, for tests, its results. */
@Stable
class RunSession(
    /** The configuration that was run, as it was then. */
    val configuration: RunConfiguration,
    /** The command that was started: the program, then its arguments. */
    val command: List<String>,
) {
    /** The lines printed so far, without colors; the oldest are dropped past a limit. */
    val lines = mutableStateListOf<String>()

    /** The colored parts of each line of [lines], at the same index. */
    val spans = mutableStateListOf<List<StyleSpan>>()

    internal val ansi = AnsiParser()

    /** How many lines were dropped from the start of [lines]. */
    var droppedLines by mutableIntStateOf(0)
        internal set

    /** How the run is going or ended. */
    var status by mutableStateOf(RunStatus.Running)
        internal set

    /** Exit code of the command, once it ended by itself. */
    var exitCode by mutableStateOf<Int?>(null)
        internal set

    /** Why the command could not start, when [status] is [RunStatus.NotStarted]. */
    var error by mutableStateOf<String?>(null)
        internal set

    /** The test results read from the output, for a configuration of kind [RunKind.Tests]. */
    var results by mutableStateOf<List<TestSuite>>(emptyList())
        internal set

    internal val parser = TestOutputParser()

    internal var stream: RunStream? = null
    internal var collecting: Job? = null

    /** Whether the user asked to stop this run: its end then counts as stopped, not failed. */
    internal var stopRequested = false

    /** Whether the command is still running. */
    val running: Boolean get() = status == RunStatus.Running
}

/**
 * The run configurations of a project and the run being shown: the ones the user saved, and the ones [discover]
 * finds in the project each time the window opens (the Composer scripts of its `composer.json` files, the wrappers
 * of Amper and Gradle), which are listed after them. [locate] finds the file a path of the output names
 * (absolute, relative to the project, or a bare file name), and [openAt] opens it at a line and column.
 */
@Stable
@Suppress("TooManyFunctions")
class RunState(
    private val rootPath: String,
    private val runner: ProjectRunner,
    private val scope: CoroutineScope,
    preferences: Map<String, String>,
    private val discover: suspend () -> List<RunConfiguration>,
    private val locate: suspend (String) -> FileEntry?,
    private val openAt: (FileEntry, Int, Int) -> Unit,
    private val openUrl: (String) -> Unit = {},
) {
    private val savedConfigurations: String? = preferences[PreferenceKeysRun.configurations(rootPath)]

    /** The configurations of the project, in the order of the selector. */
    val configurations: SnapshotStateList<RunConfiguration> =
        mutableStateListOf<RunConfiguration>().apply { addAll(decodeConfigurations(savedConfigurations)) }

    /** Id of the selected configuration, the one the Run button starts. */
    var selectedId by mutableStateOf(preferences[PreferenceKeysRun.selected(rootPath)])
        private set

    /** Ids of the configurations run last, the most recent first, as the menu of the Run widget lists them. */
    val recentIds: SnapshotStateList<String> =
        mutableStateListOf<String>().apply { addAll(decodeRecentIds(preferences[PreferenceKeysRun.recent(rootPath)])) }

    /** The configuration being edited, or `null` when the editor is closed; a new one has an id not in the list. */
    var editing by mutableStateOf<RunConfiguration?>(null)
        private set

    /** The run shown: the last one started, or the one the user picked among [sessions]. */
    var session by mutableStateOf<RunSession?>(null)
        private set

    /** Every run of the window, in the order they were started: one per configuration, several may be running. */
    val sessions: SnapshotStateList<RunSession> = mutableStateListOf()

    /** Whether any command is running. */
    val anyRunning: Boolean get() = sessions.any { it.running }

    /** The tab shown. */
    var tab by mutableStateOf(RunTab.Output)

    /** Whether the tree of results shows the failed tests only. */
    var onlyFailed by mutableStateOf(false)

    /** Name of the suite and test selected in the tree of results, to show its details. */
    var selectedTest by mutableStateOf<Pair<String, String>?>(null)

    /** The configurations found in the project, not saved; [available] leaves out those a saved one replaces. */
    var discovered by mutableStateOf<List<RunConfiguration>>(emptyList())
        private set

    // Whether the user changed the saved configurations, or some were saved: only then are they written back.
    private var loaded = savedConfigurations != null

    /** The saved configurations, then the ones discovered in the project, as the selector lists them. */
    val available: List<RunConfiguration>
        get() {
            val savedIds = configurations.map { it.id }.toSet()
            return configurations.toList() + discovered.filter { it.id !in savedIds }
        }

    /**
     * [available] grouped by the folder they run in (relative to the project, blank for the project itself): the
     * project first, then the folders by name; the configurations of a folder keep their order.
     */
    val byFolder: List<Pair<String, List<RunConfiguration>>>
        get() = groupByFolder(available)

    /** The selected configuration, if the selection names one that exists. */
    val selected: RunConfiguration? get() = available.firstOrNull { it.id == selectedId }

    /** The configurations run last (the ones that still exist), the most recent first. */
    val recent: List<RunConfiguration>
        get() {
            val all = available
            return recentIds.mapNotNull { id -> all.firstOrNull { it.id == id } }
        }

    /**
     * [recent] grouped by the folder they run in (blank for the project): the folder of the most recent command
     * first, and the commands of a folder from the most recent.
     */
    val recentByFolder: List<Pair<String, List<RunConfiguration>>>
        get() = recent.groupBy { it.workingDirectory.trim().trim('/', '\\') }.toList()

    /** Whether the configuration [id] is being run. */
    fun isRunning(id: String): Boolean = sessions.any { it.running && it.configuration.id == id }

    private fun groupByFolder(list: List<RunConfiguration>): List<Pair<String, List<RunConfiguration>>> {
        val folders = list.groupBy { it.workingDirectory.trim().trim('/', '\\') }
        return folders.toList().sortedWith(compareBy({ it.first.isNotEmpty() }, { it.first }))
    }

    /** The configurations whose name, command or folder contains every word of [query] (any case); all when blank. */
    fun search(query: String): List<RunConfiguration> {
        val lowerQuery = query.trim().lowercase()
        val words = lowerQuery.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return available
        return available.filter { configuration ->
            val haystack = "${configuration.name} ${configuration.commandLine} ${configuration.workingDirectory}"
            val lowered = haystack.lowercase()
            words.all { it in lowered }
        }
    }

    /** [search] grouped by folder, like [byFolder]. */
    fun byFolder(query: String): List<Pair<String, List<RunConfiguration>>> = groupByFolder(search(query))

    /** Looks in the project for configurations (again, the project may have changed since the window last opened). */
    fun ensureLoaded() {
        scope.launch {
            discovered = discover()
            if (selected == null) selectedId = available.firstOrNull()?.id
        }
    }

    /** Selects the configuration [id]. */
    fun select(id: String) {
        if (available.any { it.id == id }) selectedId = id
    }

    /** Opens the editor on a new configuration. */
    fun newConfiguration() {
        editing = RunConfiguration(UUID.randomUUID().toString(), "", RunKind.Run, "")
    }

    /** Opens the editor on the selected configuration. */
    fun editSelected() {
        editing = selected
    }

    /** Closes the editor without saving. */
    fun cancelEditing() {
        editing = null
    }

    /** Saves [configuration] (replacing the one with its id, or adding it) and selects it. */
    fun save(configuration: RunConfiguration) {
        val named = configuration.copy(name = configuration.name.trim().ifEmpty { configuration.commandLine.trim() })
        val index = configurations.indexOfFirst { it.id == named.id }
        if (index >= 0) configurations[index] = named else configurations += named
        loaded = true
        selectedId = named.id
        editing = null
    }

    /** Deletes the configuration [id]; the selection moves to the first one left. */
    fun delete(id: String) {
        configurations.removeAll { it.id == id }
        loaded = true
        if (selectedId == id) selectedId = available.firstOrNull()?.id
        editing = null
    }

    /** Runs the selected configuration (restarting it if it is already running). */
    fun runSelected() {
        val configuration = selected ?: return
        run(configuration)
    }

    /**
     * Runs [configuration] next to the other runs; the run it already had (if any) is stopped and replaced, so that
     * pressing Run again restarts it.
     */
    fun run(configuration: RunConfiguration) {
        val previous = sessions.firstOrNull { it.configuration.id == configuration.id }
        if (previous != null) stopSession(previous)
        remember(configuration.id)
        val command = commandOf(configuration)
        val started = RunSession(configuration, command)
        val position = sessions.indexOf(previous)
        if (position >= 0) sessions[position] = started else sessions.add(started)
        session = started
        tab = if (configuration.kind == RunKind.Tests) RunTab.Tests else RunTab.Output
        selectedTest = null
        if (command.isEmpty()) {
            started.fail(EMPTY_COMMAND)
            return
        }
        val opened = runner.start(directoryOf(configuration), command)
        if (opened == null) {
            started.fail(NO_RUNNER)
            return
        }
        started.stream = opened
        started.collecting = scope.launch { collect(opened, started) }
    }

    private fun remember(id: String) {
        recentIds.remove(id)
        recentIds.add(0, id)
        while (recentIds.size > MAX_RECENT) recentIds.removeAt(recentIds.lastIndex)
    }

    /** Shows the run [target] (one of [sessions]). */
    fun show(target: RunSession) {
        if (target !in sessions) return
        session = target
        select(target.configuration.id)
        tab = if (target.configuration.kind == RunKind.Tests) RunTab.Tests else RunTab.Output
        selectedTest = null
    }

    /** Stops the run shown, or the last one started that still runs; its last output still comes. */
    fun stop() {
        val shown = session?.takeIf { it.running }
        val target = shown ?: sessions.lastOrNull { it.running }
        if (target != null) stopSession(target)
    }

    /** Stops every run in progress. */
    fun stopAll() {
        sessions.filter { it.running }.forEach(::stopSession)
    }

    /** Removes the run [target] from the window, stopping it first if it runs. */
    fun close(target: RunSession) {
        stopSession(target)
        val position = sessions.indexOf(target)
        if (position < 0) return
        sessions.removeAt(position)
        target.collecting?.cancel()
        if (session === target) session = sessions.getOrNull(minOf(position, sessions.lastIndex))
    }

    private fun stopSession(target: RunSession) {
        val running = target.stream ?: return
        val stoppable = target.running && !target.stopRequested
        if (!stoppable) return
        target.stopRequested = true
        runner.stop(running)
    }

    /** Stops everything when the project closes. */
    fun dispose() {
        stopAll()
        sessions.forEach { it.collecting?.cancel() }
    }

    /** Opens the file named by [link], if the project has it. */
    fun open(link: OutputLink) {
        val url = link.url
        if (url != null) {
            openUrl(url)
            return
        }
        scope.launch {
            val entry = locate(link.path)
            if (entry != null) openAt(entry, link.line, link.column)
        }
    }

    /** The preferences of this project: its configurations and the selected one. */
    fun toPreferences(): Map<String, String> {
        val recent = PreferenceKeysRun.recent(rootPath) to recentIds.joinToString("\n")
        if (!loaded) return if (recentIds.isEmpty()) emptyMap() else mapOf(recent)
        val saved = PreferenceKeysRun.configurations(rootPath) to encodeConfigurations(configurations.toList())
        val chosen = PreferenceKeysRun.selected(rootPath) to selectedId.orEmpty()
        return mapOf(saved, chosen, recent)
    }

    private suspend fun collect(opened: RunStream, shown: RunSession) {
        var ended = false
        opened.chunks.collect { chunk ->
            if (chunk.finished) ended = true
            apply(shown, chunk)
        }
        if (!ended && shown.running) shown.status = RunStatus.Lost
    }

    private fun apply(shown: RunSession, chunk: RunChunk) {
        if (chunk.lines.isNotEmpty()) append(shown, chunk.lines)
        if (!chunk.finished) return
        shown.exitCode = chunk.exitCode
        shown.error = chunk.error
        shown.status =
            when {
                chunk.error != null -> RunStatus.NotStarted
                shown.stopRequested || chunk.exitCode == null -> RunStatus.Stopped
                chunk.exitCode == 0 -> RunStatus.Succeeded
                else -> RunStatus.Failed
            }
    }

    private fun append(shown: RunSession, received: List<String>) {
        val styled = received.map(shown.ansi::parse)
        val clean = styled.map { it.text }
        shown.lines.addAll(clean)
        shown.spans.addAll(styled.map { it.spans })
        val excess = shown.lines.size - MAX_LINES
        if (excess > 0) {
            shown.lines.removeRange(0, excess)
            shown.spans.removeRange(0, excess)
            shown.droppedLines += excess
        }
        if (shown.configuration.kind == RunKind.Tests) {
            clean.forEach(shown.parser::accept)
            shown.results = shown.parser.results()
        }
    }

    private fun RunSession.fail(reason: String) {
        error = reason
        status = RunStatus.NotStarted
    }

    // The program of a command written `./name` is looked up in the working directory, not in the PATH.
    private fun commandOf(configuration: RunConfiguration): List<String> {
        val words = splitCommandLine(configuration.commandLine)
        val program = words.firstOrNull().orEmpty()
        val relative = program.startsWith("./") || program.startsWith(".\\")
        if (!relative) return words
        val directory = directoryOf(configuration).trimEnd('/', '\\')
        val resolved = directory + separatorOfRoot() + program.substring(2)
        return listOf(resolved) + words.drop(1)
    }

    private fun directoryOf(configuration: RunConfiguration): String {
        val relative = configuration.workingDirectory.trim().trim('/', '\\')
        if (relative.isEmpty()) return rootPath
        return rootPath.trimEnd('/', '\\') + separatorOfRoot() + relative
    }

    // The separator the server's paths use: `/` on Linux and macOS, `\` on Windows.
    private fun separatorOfRoot(): Char = if (rootPath.startsWith('/')) '/' else '\\'
}

/** The preference keys of the Run tool window. */
object PreferenceKeysRun {
    /** Key of the configurations of the project at [root]. */
    fun configurations(root: String): String = PreferenceKeys.RUN_CONFIGURATIONS_PREFIX + root

    /** Key of the recently run configurations of the project at [root]. */
    fun recent(root: String): String = PreferenceKeys.RUN_RECENT_PREFIX + root

    /** Key of the selected configuration of the project at [root]. */
    fun selected(root: String): String = PreferenceKeys.RUN_SELECTED_PREFIX + root
}

// Configurations remembered as recent.
private const val MAX_RECENT = 6

private fun decodeRecentIds(text: String?): List<String> = text.orEmpty().lines().filter { it.isNotEmpty() }
