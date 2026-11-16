package org.example.kotlineditor.state

import kotlinx.coroutines.CoroutineScope
import org.example.kotlineditor.model.AppServices
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.model.GitCommit
import org.example.kotlineditor.model.GitCommitFile
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.model.PluginServer
import org.example.kotlineditor.syntax.Declaration

/**
 * What the UI of a feature needs from the open project; `ProjectState` implements it in `shared`. It is the one
 * seam of the IDE client: the states the features keep are published under [ServiceKey]s and reached through
 * [service], and the actions they share (opening a file, a diff, navigating) are declared here once.
 */
@Suppress("TooManyFunctions", "ComplexInterface")
interface ProjectHost {
    val root: FileEntry
    val toolWindows: ToolWindowLayout

    /** Cancelled when the project is closed, so no background work outlives it. */
    val scope: CoroutineScope

    /** The server the project runs on: its files, git, terminal, runner, search... */
    val services: AppServices

    /** The methods the server halves of plugins answer. */
    val pluginServer: PluginServer

    /** The path selected in the project view; null when none. */
    val selectedPath: String?

    /** Git status of the changed files, by path relative to the root. */
    val gitStatus: Map<String, GitFileStatus>

    /** "Always Select Opened File": the active file is selected and revealed in the project tree. */
    var autoSelectOpenedFile: Boolean

    /** How the inlay hints of the language servers are shown in the editors. */
    var inlayHintsMode: InlayHintsMode

    /** Shows [window] if hidden, starting what it needs (terminal session, Git status...). */
    fun showToolWindow(window: ToolWindow)

    /** Shows [window] if hidden, hides it if shown, starting what it needs when it appears. */
    fun toggleToolWindow(window: ToolWindow)

    /** The state a feature keeps in the project, published under [key]; fails for a feature that is not loaded. */
    fun <T : Any> service(key: ServiceKey<T>): T

    /** The state a feature keeps in the project, or null when the feature is not loaded in this project. */
    fun <T : Any> serviceOrNull(key: ServiceKey<T>): T?

    /** Publishes the state built by [state] under [key] for this project; closing the result takes it away. */
    fun <T : Any> provide(key: ServiceKey<T>, state: () -> T): AutoCloseable

    /** Entry of the file at [relativePath] (paths are relative to the project root, with `/` separators). */
    fun entryAt(relativePath: String): FileEntry

    /** Opens [entry] in the editor, as a click in the project tree does. */
    fun openFile(entry: FileEntry)

    /** Opens [entry] with [start]..[end] selected, remembering the current position for Back. */
    fun navigate(entry: FileEntry, start: TextPos, end: TextPos)

    /** Looks at the disk again (tree, Git, open editors), as on focus or after an IDE action wrote many files. */
    fun synchronize()

    /** Opens the diff of the working tree file at [relativePath] against HEAD. */
    fun openDiff(relativePath: String)

    /** Opens the three-way merge of the conflicted working tree file at [relativePath]. */
    fun openMerge(relativePath: String)

    /** Opens a review tab, led by hand, of the working tree files at [relativePaths]. */
    fun openManualReview(relativePaths: List<String>)

    /** Opens the diff of [file] in [commit]. */
    fun openCommitDiff(commit: GitCommit, file: GitCommitFile)

    /** Opens the Branches tool window on the history of [ref], or of the current branch. */
    fun showHistory(ref: String?)

    /** The declarations of [document] from its language server; null when none answers (it is outlined locally). */
    suspend fun serverOutline(document: Document): List<Declaration>?

    /** The declarations of the project named like [query], from its language servers. */
    suspend fun serverSymbols(query: String): List<SymbolMatch>
}

/** Names the state a feature keeps in the project, so that another module reaches it through [ProjectHost.service]. */
class ServiceKey<T : Any>(
    /** For messages. */
    val name: String,
)
