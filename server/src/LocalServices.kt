package org.example.kotlineditor.server

import org.example.kotlineditor.acp.AgentSettings
import org.example.kotlineditor.lsp.DiskChange
import org.example.kotlineditor.lsp.LanguageServerSettings
import org.example.kotlineditor.model.FileOperation
import org.example.kotlineditor.model.OperatedFile
import org.example.kotlineditor.model.ServerPlaces
import org.example.kotlineditor.server.ripgrep.RipgrepSettings
import org.example.kotlineditor.telemetry.Telemetry
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Everything the server does on its machine. [dataDirectory] holds recent projects and sessions; [ripgrep] says
 * where Find in Files gets its ripgrep executable.
 */
class LocalServices(
    /** The folder of the server's own data (recent projects, sessions, the plugins' files). */
    val dataDirectory: File = File(System.getProperty("user.home"), ".kotlin-editor"),
    maxFileSize: Long = TextFiles.DEFAULT_MAX_SIZE,
    ripgrep: RipgrepSettings = RipgrepSettings(),
    /** Where the language servers are found and how they start (the Language Servers plugin reads it). */
    val languageServerSettings: LanguageServerSettings = LanguageServerSettings(dataDirectory.toPath()),
    /** The agents' registry and processes (the Agents plugin reads it). */
    val agentSettings: AgentSettings = AgentSettings(),
    /** The command that runs OpenSSH for the deployments to another machine (the Deploy plugin reads it). */
    val ssh: List<String> = listOf("ssh"),
) {
    private val components = ConcurrentHashMap<String, Any>()
    private val observers = CopyOnWriteArrayList<DiskObserver>()
    private val closers = CopyOnWriteArrayList<AutoCloseable>()

    /** What the server measures with (the language servers' starts), set by the server that uses these services. */
    @Volatile var telemetry: Telemetry = Telemetry.Disabled

    /**
     * The worktrees of agent mode of a repository (directory to branch), none until the Git plugin sets how to read
     * them: agents work in Git worktrees, which Git alone knows.
     */
    @Volatile var agentWorktrees: (String) -> Map<String, String> = { emptyMap() }

    /** Files of the projects. */
    val fileSystem = LocalFileSystem(maxFileSize)

    /** Project-wide queries: Find in Files with ripgrep, the built-in search otherwise. */
    val search = RipgrepSearch(LocalSearch(fileSystem), ripgrep, maxFileSize)

    /** What changes on the disk of the projects, whoever changes it; the language servers are told too. */
    val disk = ProjectWatches { changes -> diskChanged(changes) }

    /** Recently opened projects. */
    val recentProjects = FileRecentProjectsStore(File(dataDirectory, "recent-projects.txt"))

    /** Open files of each project. */
    val sessions = FileSessionStore(File(dataDirectory, "sessions"))

    /** Places of this machine, for the client's Open Project dialog. */
    val places = LocalPlaces()

    /** Whether an observer is active: the files of a directory are only listed before a rename when one is. */
    val observed: Boolean get() = observers.any { it.active }

    /**
     * The component [name] of a plugin, created by the first plugin half that asks: a server restarted on the same
     * services (and a second contribution of the same plugin) finds the one that exists.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> component(name: String, create: () -> T): T = components.computeIfAbsent(name) { create() } as T

    /** Tells [observer] what changes on the disk and what the IDE does to files, until the services close. */
    fun observe(observer: DiskObserver) {
        observers.add(observer)
    }

    /** Closes [closer] with the services, after what was registered before it is closed (a plugin's processes). */
    fun closeWith(closer: AutoCloseable) {
        closers.add(closer)
    }

    /** Tells the active observers that [changes] happened on the disk. */
    fun diskChanged(changes: List<Pair<String, DiskChange>>) {
        if (changes.isEmpty()) return
        observers.filter { it.active }.forEach { it.diskChanged(changes) }
    }

    /** Tells the active observers that the IDE did [operation] on [files]. */
    fun filesOperated(operation: FileOperation, files: List<OperatedFile>) {
        observers.filter { it.active }.forEach { it.filesOperated(operation, files) }
    }

    /** Tells the observers that the client session [id] ended, to free what they kept for it. */
    fun sessionEnded(id: String) {
        observers.filter { it.active }.forEach { it.sessionEnded(id) }
    }

    /** Stops what runs in the background: the watches of the disk and what the plugins registered. */
    fun close() {
        disk.close()
        closers.reversed().forEach { it.close() }
    }
}

/**
 * What a plugin that follows the files (the language servers) is told: the changes of the disk, whoever makes them,
 * and the operations of the IDE on files. It is told only while [active], so that a plugin not in use costs nothing.
 */
interface DiskObserver {
    /** Whether the observer is in use and wants to be told. */
    val active: Boolean

    /** [changes] happened on the disk. */
    fun diskChanged(changes: List<Pair<String, DiskChange>>)

    /** The IDE did [operation] on [files]. */
    fun filesOperated(operation: FileOperation, files: List<OperatedFile>)

    /** The client session [session] ended. */
    fun sessionEnded(session: String)
}

/** [ServerPlaces] on this machine: its home directory. */
class LocalPlaces : ServerPlaces {
    override suspend fun home(): String? = System.getProperty("user.home")
}
