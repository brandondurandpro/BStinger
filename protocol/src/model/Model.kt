package org.example.kotlineditor.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/**
 * A change sent to a server that restarted before answering: it may have been applied or not, and is not sent
 * again (it could apply twice). The caller reads the state again to know.
 */
class OutcomeUnknownException(
    /** What was asked, the name of the remote method. */
    val operation: String,
) : IOException("The server restarted before confirming $operation: whether it was applied is unknown")

/** Every service the IDE uses. */
class AppServices(
    /** Files. */
    val fileSystem: ProjectFileSystem,
    /** Project-wide queries. */
    val search: ProjectSearch,
    /** Recently opened projects. */
    val recentProjects: RecentProjectsStore,
    /** Places of the server (its home directory), for the Open Project dialog. */
    val places: ServerPlaces = ServerPlaces.None,
    /** Terminal shells. */
    val terminal: TerminalLauncher,
    /** Open files of each project. */
    val sessions: SessionStore = InMemorySessionStore(),
    /** Git information. */
    val git: GitClient = GitClient.None,
    /** State of the link with the server. */
    val connection: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.Connected),
    /**
     * Number of connections opened to the server, one more at each reconnection (a restarted server forgot what it
     * was told): follow it rather than [connection], which a quick reconnection leaves at Connected.
     */
    val connections: StateFlow<Long> = MutableStateFlow(1L),
    /** Capture of the frames exchanged with the server (devtools), when there is a server. */
    val network: NetworkInspector? = null,
    /** Language servers run by the server. */
    val languageServers: LanguageServers = LanguageServers.None,
    /** The telemetry consent of the server. */
    val telemetry: TelemetrySettings = TelemetrySettings.None,
    /** What other programs change on the disk of the server. */
    val disk: DiskWatch = DiskWatch.None,
    /** Conversations with the agents the server runs (agent mode). */
    val agents: Agents = Agents.None,
    /** Commands run in the projects of the server: run configurations and tests. */
    val runner: ProjectRunner = ProjectRunner.None,
    /** The methods the server halves of plugins answer. */
    val plugins: PluginServer = PluginServer.None,
    /** Deployment of files to a folder, on the server's disk or on another machine. */
    val deployer: Deployer = Deployer.None,
    /** The deployments saved for each project. */
    val deployProfiles: DeployProfiles = InMemoryDeployProfiles(),
)

private val UNIX_SEPARATORS = charArrayOf('/')
private val WINDOWS_SEPARATORS = charArrayOf('/', '\\')

/**
 * The characters that separate the names of [path]: `/`, and `\` too in a Windows path (`C:\p\a.kt`, or
 * `C:\p/a.kt` when rebuilt from Git). The paths of a Linux or macOS server start with `/`, and a `\` there is a
 * character of a name.
 */
fun separatorsOf(path: String): CharArray = if (path.startsWith('/')) UNIX_SEPARATORS else WINDOWS_SEPARATORS

/**
 * [path] with `/` between its names, whatever its separators (see [separatorsOf]); [root] is an absolute path of the
 * same server, needed when [path] is relative.
 */
fun slashed(path: String, root: String = path): String = if (root.startsWith('/')) path else path.replace('\\', '/')

/** [path], below the directory [root], relative to it with `/` separators (`src/a.kt`). */
fun relativeToRoot(path: String, root: String): String {
    val relative = path.removePrefix(root).trimStart(*separatorsOf(root))
    return slashed(relative, root)
}

/** Last segment of a path, whatever the separator. */
fun pathName(path: String): String {
    val separators = separatorsOf(path)
    val trimmed = path.trimEnd(*separators)
    val name = trimmed.substring(trimmed.lastIndexOfAny(separators) + 1)
    return name.ifEmpty { path }
}

/**
 * The directory that contains [path], or `null` when it has none (a drive or filesystem root). Reads the path
 * strings only, without touching the disk, whatever the separators; paths are those of the server's platform.
 */
fun parentPath(path: String): String? = java.io.File(path.ifEmpty { "/" }).parent
