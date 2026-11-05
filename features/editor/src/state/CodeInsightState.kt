package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CodeIntelligence
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.TextChange

private const val HIGHLIGHT_DELAY_MS = 250L

/** What the user reads when the language server cannot answer yet (starting, or out of reach). */
internal const val NOT_READY = "The language server is not ready"

/** Said while usages or declarations wait for the language server to index the project. */
internal const val INDEXING = "Indexing the project… the results will show once it is done"

/**
 * Code intelligence in the editor (see #97), like IntelliJ's: documentation on hover and Ctrl+Q, Ctrl+click and
 * Ctrl+B to the declaration (its usages when already on it), Ctrl+Alt+B and Ctrl+Shift+B, usages with Alt+F7 and
 * a "3 usages" hint after each declaration, problems underlined, occurrences of the symbol at the caret, parameter
 * info, rename (Shift+F6), reformat (Ctrl+Alt+L) and quick fixes (Alt+Enter, see `CodeInsightEdits.kt`). Each
 * request is about the version of the document it was made on: an answer for an older text, or after the user
 * moved on, is dropped.
 */
@Stable
@Suppress("TooManyFunctions")
class CodeInsightState(
    internal val scope: CoroutineScope,
    backend: () -> InsightBackend?,
    internal val host: InsightHost,
) {
    private val requests = InsightRequests(backend)
    private val notices = InsightNotices(scope) { requests.indexing }
    private val documents = DocumentInsights(scope, requests, ::onEdit, ::askMore)
    private val hovers = HoverInsight(scope, requests, notices) { insightOf(it)?.diagnostics.orEmpty() }
    private val signatures = SignatureInsight(scope, requests, notices)
    private val navigation = NavigationInsight(scope, requests, notices, host, ::dismissHover)

    /** The documentation shown, null when none. */
    val hover: HoverPopup? get() = hovers.popup

    /** The list of places shown, null when none. */
    val locations: LocationsPopup? get() = navigation.locations

    /** The parameter info shown, null when none. */
    val signature: SignaturePopup? get() = signatures.popup

    /** The rename popup, null when none. */
    var rename: RenamePopup? by mutableStateOf(null)
        internal set

    /** The quick fixes shown, null when none. */
    var codeActions: CodeActionsPopup? by mutableStateOf(null)
        internal set

    /** The list of the problems of a document, null when none is shown (see `ProblemsList.kt`). */
    var problems: ProblemsList? by mutableStateOf(null)
        internal set

    /** The call or type hierarchy shown, null when none. */
    var hierarchy: HierarchyPopup? by mutableStateOf(null)
        internal set

    /**
     * How the inlay hints are shown; changing it shows those already known the new way. Chosen in the Language
     * Servers window, saved with the preferences.
     */
    var inlayHintsMode: InlayHintsMode
        get() = documents.inlayMode
        set(value) {
            documents.inlayMode = value
        }

    /** The document whose "3 usages" hint is under the mouse (the pointer is a hand over it), null when none. */
    var hintUnderMouse: Document? by mutableStateOf(null)

    /** The ranges Ctrl+W walks through, from the last answer of the server (see `SmartSelection.kt`). */
    internal var selectionSteps: SelectionSteps? = null

    /** The message shown, null when none. */
    val notice: InsightNotice? get() = notices.notice

    /** The word underlined under the mouse while Ctrl is held. */
    var link: LinkTarget? by mutableStateOf(null)
        internal set

    /** Whether the mouse is over the documentation popup: it stays while it is. */
    val hoverPopupHovered: Boolean get() = hovers.popupHovered

    private var highlightJob: Job? = null

    /** What the language server said about [document], null when it has none. */
    fun insightOf(document: Document): DocumentInsight? = documents.insightOf(document)

    /** The documents open with a language server, by path: new ones are followed, closed ones forgotten. */
    fun track(documents: Map<String, Document>) = this.documents.track(documents)

    /** The server sent newer diagnostics. */
    fun applyDiagnostics(snapshot: DiagnosticsSnapshot) = documents.applyDiagnostics(snapshot)

    /**
     * The language server restarted, another one serves the files, or it began or ended indexing: the usage counts
     * are asked again. A document still asking finishes first, then asks again: phpactor reports each search of
     * references as a progress, so cancelling it at each change would restart its counts forever.
     */
    fun refreshAll() = documents.refreshAll()

    private fun onEdit(insight: DocumentInsight, change: TextChange) {
        insight.follow(change)
        formatAfterTyping(insight, change)
        documents.applyInlays(insight)
        documents.refreshUsages(insight, delayed = true)
        dismissHover()
        notices.hide()
        if (codeActions?.document === insight.document) codeActions = null
        if (locations?.document === insight.document) navigation.locations = null
    }

    // What the other files of the code intelligence ask about a document, after its usage counts.
    private suspend fun askMore(insight: DocumentInsight) {
        codeLensesFromServer(insight)
        documentLinksFromServer(insight)
        formatTriggersFromServer(insight)
    }

    // --- Hover

    /**
     * The mouse rests at [pos] of [document] ([pos] null: not over a word). The documentation comes after a short
     * pause; with [ctrl], the word is underlined like a link.
     */
    fun mouseAt(path: String, document: Document, pos: TextPos?, ctrl: Boolean) {
        val word = pos?.let { wordRange(document, it) }
        // With Ctrl, a link of the server is underlined whole (a path, an address), otherwise the word.
        val linkRange = pos?.let { linkAt(document, it) }?.range
        val linked = (linkRange ?: word)?.takeIf { ctrl }
        link = linked?.let { LinkTarget(document, it.startLine, it.startColumn, it.endColumn) }
        hovers.mouseAt(path, document, pos, word)
    }

    /** The mouse left the code: the documentation goes, unless the mouse went onto it. */
    fun mouseLeft() {
        link = null
        hovers.mouseLeft()
    }

    /** The mouse entered ([inside]) or left the documentation popup. */
    fun hoverPopupHovered(inside: Boolean) = hovers.popupHovered(inside)

    /** Ctrl was released: no link any more. */
    fun ctrlReleased() {
        link = null
    }

    /** Ctrl+Q: the documentation of the symbol at the caret. */
    fun quickDocumentation(path: String, document: Document) = hovers.quickDocumentation(path, document)

    /** Closes the documentation. */
    fun dismissHover() = hovers.dismiss()

    // --- Navigation

    /**
     * Ctrl+B or Ctrl+click at [pos]: goes to the declaration; on the declaration itself, shows its usages. Several
     * declarations are listed to choose from. Without a ready language server, the declaration is searched by name.
     */
    fun gotoDeclaration(path: String, document: Document, pos: TextPos) =
        navigation(path, document, pos, NavigationKind.Definition)

    /** Ctrl+Shift+B: the declaration of the type of the symbol at the caret. */
    fun gotoTypeDeclaration(path: String, document: Document) =
        navigation(path, document, document.caret, NavigationKind.TypeDefinition)

    /** Ctrl+Alt+B: the implementations of the symbol at the caret. */
    fun gotoImplementation(path: String, document: Document) =
        navigation(path, document, document.caret, NavigationKind.Implementation)

    private fun navigation(path: String, document: Document, pos: TextPos, kind: NavigationKind) {
        link = null
        navigation.navigate(path, document, pos, kind)
    }

    /** Alt+F7, or a click on a "3 usages" hint: the usages of the symbol at [pos]; one usage is opened directly. */
    fun showUsages(path: String, document: Document, pos: TextPos) = navigation.showUsages(path, document, pos)

    internal fun showLocations(title: String, locations: List<SourceLocation>, document: Document, anchor: TextPos) =
        navigation.showLocations(title, locations, document, anchor)

    /** Moves the selection of the list of places or of quick fixes by [delta], wrapping around. */
    fun moveSelection(delta: Int) {
        locations?.let { popup -> popup.selected = (popup.selected + delta).mod(popup.locations.size) }
        codeActions?.let { popup -> popup.selected = (popup.selected + delta).mod(popup.actions.size.coerceAtLeast(1)) }
    }

    /** Opens the place [index] of the list, or the selected one. */
    fun openLocation(index: Int? = null) = navigation.openLocation(index)

    /** Closes the list of places. */
    fun dismissLocations() {
        navigation.locations = null
    }

    // --- Highlights and parameter info

    /** The caret of [document] moved or its text changed: the occurrences of the symbol there are asked again. */
    fun caretMoved(path: String, document: Document) {
        val insight = insightOf(document) ?: return
        highlightJob?.cancel()
        insight.highlights = emptyList()
        val caret = document.caret
        val version = document.version
        val word = wordRange(document, caret)
        if (!document.hasSelection && word != null) {
            highlightJob =
                scope.launch {
                    delay(HIGHLIGHT_DELAY_MS)
                    val found = askAt(path, document, caret) { servers, at -> servers.highlights(at) }.orEmpty()
                    val unchanged = document.version == version && document.caret == caret
                    // A lone occurrence is not worth highlighting, like IntelliJ.
                    if (unchanged && found.size > 1) insight.highlights = found
                }
        }
        if (signature?.document === document) signatures.request(path, document, explicit = false)
    }

    /** Text was typed: `(` and `, ` open the parameter info, which follows the caret while it is shown. */
    fun edited(path: String, document: Document) {
        val caret = document.caret
        val before = document.lines.getOrNull(caret.line)?.getOrNull(caret.column - 1)
        val opens = before == '(' || before == ','
        if (opens || signature?.document === document) signatures.request(path, document, explicit = false)
    }

    /** Ctrl+P: the parameter info at the caret. */
    fun parameterInfo(path: String, document: Document) = signatures.request(path, document, explicit = true)

    /** Closes the parameter info. */
    fun dismissSignature() = signatures.dismiss()

    // --- Problems

    /** F2 (Shift+F2 with [backward]): moves the caret to the next problem of [document], wrapping around. */
    fun nextProblem(document: Document, backward: Boolean = false) {
        val problems = insightOf(document)?.diagnostics.orEmpty()
        val caret = document.caret
        val starts = problems.map { TextPos(it.range.startLine, it.range.startColumn) }.distinct().sorted()
        val target =
            if (backward) {
                starts.lastOrNull { it < caret } ?: starts.lastOrNull()
            } else {
                starts.firstOrNull { it > caret } ?: starts.firstOrNull()
            }
        target?.let { document.moveTo(it) }
    }

    /** Closes the message. */
    fun dismissNotice() = notices.dismiss()

    /** Escape: closes what is shown over the code; false when nothing was. */
    fun escape(): Boolean {
        val popups = listOf(hover, locations, signature, codeActions, notice, rename, problems, hierarchy)
        val shown = popups.any { it != null }
        problems = null
        hierarchy = null
        dismissHover()
        navigation.locations = null
        dismissSignature()
        codeActions = null
        rename = null
        dismissNotice()
        return shown
    }

    internal fun notify(document: Document, anchor: TextPos, text: String) = notices.notify(document, anchor, text)

    /** Asks about [pos] of [document], as the server has it; null when it does not mirror the document. */
    internal suspend fun <T> askAt(
        path: String,
        document: Document,
        pos: TextPos,
        request: suspend (CodeIntelligence, DocumentPosition) -> T?,
    ): T? = requests.askAt(path, document, pos, request)

    /** Asks about the version of [document] the server has; null when it does not mirror the document. */
    internal suspend fun <T> askVersion(
        path: String,
        document: Document,
        request: suspend (CodeIntelligence, Int) -> T?,
    ): T? = requests.askVersion(path, document, request)

    /** Asks the language servers something that is about no document. */
    internal suspend fun <T> askServers(request: suspend (CodeIntelligence) -> T?): T? = requests.askServers(request)
}
