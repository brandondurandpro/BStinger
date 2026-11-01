package org.example.kotlineditor.model

/** Where a language server is in its life (see #26, "Process lifecycle"). */
enum class LanguageServerState {
    /** The process is starting, or answering `initialize`. */
    Starting,

    /** Running, and reporting progress (usually the first indexing of the project). */
    Indexing,

    /** Running and ready. */
    Running,

    /** Waiting before starting again after a crash. */
    Restarting,

    /** Stopped by the user, or because nobody used it any more. */
    Stopped,

    /** Crashed too often: it stays down until the user restarts it. */
    Crashed,

    /** Cannot run here: runtime missing, download failed... [LanguageServerInfo.message] says why. */
    Unavailable,
}

/** Memory of a process tree, in bytes; see `ProcessMemory` in the `lsp` module for what private means. */
data class MemoryUsage(
    /** Memory no other process shares, summed over the tree: the number shown and watched for leaks. */
    val privateBytes: Long,
    /** Whole resident set, summed over the tree. */
    val residentBytes: Long,
    /** How many processes the tree has (the server and its children). */
    val processCount: Int,
)

/** One language server process, as the IDE shows it. */
data class LanguageServerInfo(
    /** Stable identifier across restarts: the server and its root. */
    val id: String,
    /** Which server (`phpactor`). */
    val serverName: String,
    /** Language identifier (`php`). */
    val language: String,
    /** Name of the language for people (`PHP`). */
    val languageName: String,
    /** Directory it serves (the closest `composer.json` for PHP, the project otherwise). */
    val root: String,
    /** Life state. */
    val state: LanguageServerState,
    /** Number of the current process, increased by every start; makes a restart request idempotent. */
    val generation: Long,
    /** Process id while running. */
    val pid: Long?,
    /** Last memory sample, null when not running or not measurable on this OS. */
    val memory: MemoryUsage?,
    /** Highest private memory since it started. */
    val peakPrivateBytes: Long,
    /** Indexing progress in percent, when the server reports one. */
    val progressPercent: Int?,
    /** What the server says it is doing ("Indexing"), when it reports progress. */
    val progressTitle: String?,
    /** Verdict of the leak detector (`Normal`, `Growing`, `SuspectedLeak`...). */
    val leakVerdict: String,
    /** Explanation of [leakVerdict], for the tooltip. */
    val leakReason: String,
    /** Why it is [LanguageServerState.Unavailable], [LanguageServerState.Crashed] or restarting. */
    val message: String?,
    /** Crashes in the last 5 minutes. */
    val recentCrashes: Int,
    /** How many IDE sessions use it. */
    val users: Int,
)

/** Memory of the IDE server itself, shown next to the language servers. */
data class ServerMemory(
    /** JVM heap in use. */
    val heapUsedBytes: Long,
    /** JVM heap limit. */
    val heapMaxBytes: Long,
    /** Resident memory of the whole server process (0 when not measurable). */
    val residentBytes: Long,
)

/** Every language server and the IDE server's memory, as one snapshot: a newer one replaces an older one. */
data class LanguageServersStatus(
    /** Increases with every change; the client keeps the snapshot with the highest. */
    val version: Long,
    /** The IDE server's own memory. */
    val server: ServerMemory,
    /** The language servers, in a stable order. */
    val servers: List<LanguageServerInfo>,
) {
    /** Snapshots shared by every client. */
    companion object {
        /** Before the first answer of the server. */
        val Empty = LanguageServersStatus(0, ServerMemory(0, 0, 0), emptyList())
    }
}

/** A language server the user may choose for a language, with what helps choosing it. */
data class LanguageServerCandidateInfo(
    /** Identifier saved with the choice (`phpactor`). */
    val id: String,
    /** Name shown. */
    val displayName: String,
    /** Licence in a few words. */
    val licence: String,
    /** What it does well. */
    val strengths: List<String>,
    /** What it does less well. */
    val drawbacks: List<String>,
    /** Whether it can run on the server machine. */
    val available: Boolean,
    /** What was detected there, one sentence per requirement. */
    val details: List<String>,
)

/** The servers offered for a language, and the one already chosen. */
data class LanguageServerChoice(
    /** Language identifier (`php`). */
    val language: String,
    /** Name of the language for people (`PHP`). */
    val languageName: String,
    /** The candidates, in the order to show them. */
    val candidates: List<LanguageServerCandidateInfo>,
    /** Id of the chosen candidate, null when none was chosen yet. */
    val chosen: String?,
)

/** Language servers running on the server machine for the open projects. */
interface LanguageServers {
    /**
     * The status once its version is greater than [since], or the current one after a while (a long poll): a
     * newer snapshot replaces an older one, so a late or repeated answer never shows an older state.
     */
    suspend fun status(since: Long): LanguageServersStatus

    /**
     * Declares the files this IDE session has open in [project], replacing what it declared before: the servers
     * they need are started, the ones nobody needs any more stop a few minutes later. Returns the languages that
     * need a server and have none chosen yet, to offer the choice.
     */
    suspend fun use(project: String, files: List<String>): List<String>

    /** Restarts [id] if its current process is still [generation]: a request sent twice restarts it once. */
    suspend fun restart(id: String, generation: Long)

    /** Stops [id] until [start]. */
    suspend fun stop(id: String)

    /** Starts [id] again after [stop] or a crash. */
    suspend fun start(id: String)

    /** Last lines the server wrote on stderr or logged, oldest first. */
    suspend fun log(id: String): List<String>

    /** The servers offered for [language] in [project]. */
    suspend fun choice(language: String, project: String): LanguageServerChoice

    /** Saves [candidate] as the server of [language] (for every project), and switches the running ones. */
    suspend fun choose(language: String, project: String, candidate: String)

    /** No language servers (an IDE without a server, most unit tests). */
    object None : LanguageServers {
        override suspend fun status(since: Long): LanguageServersStatus = kotlinx.coroutines.awaitCancellation()

        override suspend fun use(project: String, files: List<String>): List<String> = emptyList()

        override suspend fun restart(id: String, generation: Long) = Unit

        override suspend fun stop(id: String) = Unit

        override suspend fun start(id: String) = Unit

        override suspend fun log(id: String): List<String> = emptyList()

        override suspend fun choice(language: String, project: String) =
            LanguageServerChoice(language, language, emptyList(), null)

        override suspend fun choose(language: String, project: String, candidate: String) = Unit
    }
}
