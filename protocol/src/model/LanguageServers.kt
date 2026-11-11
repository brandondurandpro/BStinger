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

/** Memory of a process tree, in bytes; see `ProcessMemory` for what private means. */
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
    /**
     * Why it shows no inlay hints (intelephense: they need its Premium licence), once it answered `initialize`
     * without them; null when it gives them or has not started.
     */
    val inlayHintsNote: String? = null,
    /** A dependency the project lacks that costs features (Vue templates without `vue` installed); null when none. */
    val setupNote: String? = null,
)

/** Memory of the IDE server itself, shown next to the language servers. */
data class ServerMemory(
    /** JVM heap in use. */
    val heapUsedBytes: Long,
    /** JVM heap limit. */
    val heapMaxBytes: Long,
    /** Resident memory of the whole server process (0 when not measurable). */
    val residentBytes: Long,
    /** Process id of the server (0 when an older server does not say). */
    val pid: Long = 0,
    /**
     * When the server process started, in milliseconds since the epoch (0 when unknown): with [pid], it tells a
     * client whether the server runs in its own process, whose memory must then be counted once.
     */
    val startedAtMillis: Long = 0,
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
@Suppress("TooManyFunctions", "ComplexInterface")
interface LanguageServers : CodeIntelligence {
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

    /**
     * Opens [path] of [project] with its whole [text] at [version], or replaces what the server had: after a
     * reconnection, or when a change was refused. The language server of the file sees it from then on.
     */
    suspend fun openDocument(project: String, path: String, version: Int, text: String)

    /**
     * Applies [changes], in order, to [path] open at `version - 1`, making it [version]. Fails when the server has
     * another version (it restarted, a change was lost): the client then opens the document again.
     */
    suspend fun changeDocument(path: String, version: Int, changes: List<TextChange>)

    /** The client closed [path]. */
    suspend fun closeDocument(path: String)

    /**
     * Completion at [line], [column] of [path] once the server has its [version]; [CompletionResult.NotReady] when no
     * language server can answer now.
     */
    suspend fun complete(path: String, version: Int, line: Int, column: Int): CompletionResult

    /**
     * The documentation (Markdown) of item [index] of the completion answer [token], asked to the language server
     * that proposed it; null when it has none or the answer was replaced.
     */
    suspend fun resolveCompletion(token: String, index: Int): String? = null

    /** No language servers (an IDE without a server, most unit tests). */
    @Suppress("TooManyFunctions")
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

        override suspend fun openDocument(project: String, path: String, version: Int, text: String) = Unit

        override suspend fun changeDocument(path: String, version: Int, changes: List<TextChange>) = Unit

        override suspend fun closeDocument(path: String) = Unit

        override suspend fun complete(path: String, version: Int, line: Int, column: Int) = CompletionResult.NotReady
    }
}

/**
 * The languages that have language servers, by file extension. A file has a *language identifier* (what the server is
 * told in `didOpen`: `typescriptreact` for a `.tsx`) and belongs to a *language* (the group one server serves:
 * TypeScript, JavaScript and their React forms are all `typescript`).
 */
object Languages {
    /** The language of `.ts`, `.tsx`, `.js`, `.jsx` and `.vue` files: one server serves them all. */
    const val TYPESCRIPT = "typescript"

    /** The language of the Vue server, which serves `.vue` files beside the TypeScript server. */
    const val VUE_SERVER = "vue"

    /** The language of the Tailwind CSS server, which serves the files of other languages beside their own server. */
    const val TAILWIND = "tailwind"

    private const val HTML = "html"
    private const val CSS = "css"
    private const val PHP = "php"

    // The languages the IDE has a server for, by extension: declared in [LanguageSupport], with their icon and more.
    private val served: Map<String, LspSupport> =
        buildMap {
            for (language in LanguageSupport.all) {
                val lsp = language.lsp ?: continue
                for (extension in language.extensions) put(extension, lsp)
            }
        }

    // What the servers are called, by the language they serve (Vue and Tailwind have no files of their own).
    private val names =
        mapOf(
            HTML to "HTML",
            CSS to "CSS",
            PHP to "PHP",
            TYPESCRIPT to "TypeScript and JavaScript",
            VUE_SERVER to "Vue",
            TAILWIND to "Tailwind CSS",
        )

    /** The names of the languages that have a server, for a test to check that each has one. */
    val serverLanguages: Set<String> get() = names.keys

    private fun lspOf(path: String): LspSupport? = served[path.substringAfterLast('.', "").lowercase()]

    /** Language identifier of [path] (`typescriptreact`), null when no language server handles it. */
    fun identifierOf(path: String): String? = lspOf(path)?.identifier

    /** Language of [path] (`typescript` for a `.tsx`, `.js`…), null when no language server handles it. */
    fun languageOf(path: String): String? = lspOf(path)?.group

    /**
     * The languages whose servers serve [path] beside the one of its own language: Tailwind CSS knows the class names
     * of the files of the web, whatever their language.
     */
    fun companionsOf(path: String): List<String> = lspOf(path)?.companions.orEmpty()

    /**
     * Whether the IDE follows [path] with the code insight of a language server (usage counts, hints, problems): not
     * the files only a companion server knows, such as `.html` and `.css`, which only get completion and hover.
     */
    fun hasInsight(path: String): Boolean = lspOf(path)?.insight == true

    /** Name of [language] for people. */
    fun nameOf(language: String): String = names[language] ?: language

    /** File whose directory is the root of a [language] server, null when the project is. */
    fun rootMarkerOf(language: String): String? {
        val servers = LanguageSupport.all.mapNotNull { it.lsp }
        val withMarker = servers.firstOrNull { it.group == language && it.rootMarker != null }
        return withMarker?.rootMarker
    }
}

/**
 * One edit of an open document: the text between two positions replaced by [text]. Positions are lines and
 * UTF-16 columns of the text before the edit, lines being separated by `\n` whatever the file uses.
 */
data class TextChange(
    /** Line where the replaced text starts. */
    val startLine: Int,
    /** Column where the replaced text starts. */
    val startColumn: Int,
    /** Line where the replaced text ends. */
    val endLine: Int,
    /** Column where the replaced text ends (excluded). */
    val endColumn: Int,
    /** The new text, lines separated by `\n`. */
    val text: String,
)

/** What a completion item is, for its icon. */
enum class CompletionKind {
    /** A function. */
    Function,

    /** A method of a class. */
    Method,

    /** A variable. */
    Variable,

    /** A class, an enum or a trait. */
    Class,

    /** An interface. */
    Interface,

    /** A constant. */
    Constant,

    /** A property of a class. */
    Property,

    /** A keyword of the language. */
    Keyword,

    /** A namespace or a module. */
    Module,

    /** Anything else. */
    Other,
}

/** One proposal of the completion popup. */
data class CompletionItem(
    /** What the popup shows. */
    val label: String,
    /** What it is. */
    val kind: CompletionKind,
    /** Its signature or type, shown on the right; null when the server gives none. */
    val detail: String?,
    /** The text that replaces the typed prefix when the item is accepted. */
    val insertText: String,
    /** Its documentation in Markdown, when the server sent it with the item. */
    val documentation: String? = null,
    /** Its index for [LanguageServers.resolveCompletion], -1 when the server cannot tell more about it. */
    val resolveIndex: Int = -1,
)

/** The answer to a completion request. */
data class CompletionResult(
    /**
     * Column of the caret's line where the replaced text starts: accepting an item replaces the text between it and
     * the caret with [CompletionItem.insertText].
     */
    val replaceFrom: Int,
    /** The items, best first, already filtered by the typed prefix and capped. */
    val items: List<CompletionItem>,
    /** More items exist than were sent, or typing more may bring others: ask again on the next keystroke. */
    val incomplete: Boolean,
    /** False when no language server could answer (none for this file, not started yet, crashed). */
    val ready: Boolean,
    /** Names this answer for [LanguageServers.resolveCompletion]; empty when its items cannot be resolved. */
    val token: String = "",
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = CompletionResult(0, emptyList(), incomplete = false, ready = false)
    }
}
