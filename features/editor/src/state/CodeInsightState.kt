package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CodeIntelligence
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.LocationsResult
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange
import java.io.IOException

private const val HOVER_DELAY_MS = 500L
private const val HOVER_HIDE_MS = 300L
private const val HIGHLIGHT_DELAY_MS = 250L
private const val USAGES_DELAY_MS = 800L
private const val NOT_READY_RETRY_MS = 1_000L
private const val NOT_READY_RETRIES = 30
private const val NOTICE_MS = 3_000L
private const val SIGNATURE_DELAY_MS = 150L

/** What the user reads when the language server cannot answer yet (starting, or out of reach). */
internal const val NOT_READY = "The language server is not ready"

/** Where a navigation was asked from, and what it looks for. */
private class NavigationRequest(
    val path: String,
    val document: Document,
    val pos: TextPos,
    val kind: NavigationKind,
    val word: TextRange?,
) {
    val anchor: TextPos get() = word?.let { TextPos(it.startLine, it.startColumn) } ?: pos
    val name: String? get() = word?.let { document.lines[it.startLine].substring(it.startColumn, it.endColumn) }
}

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
    private val backend: () -> InsightBackend?,
    internal val host: InsightHost,
) {
    /** The documentation shown, null when none. */
    var hover: HoverPopup? by mutableStateOf(null)
        private set

    /** The list of places shown, null when none. */
    var locations: LocationsPopup? by mutableStateOf(null)
        private set

    /** The parameter info shown, null when none. */
    var signature: SignaturePopup? by mutableStateOf(null)
        private set

    /** The rename popup, null when none. */
    var rename: RenamePopup? by mutableStateOf(null)
        internal set

    /** The quick fixes shown, null when none. */
    var codeActions: CodeActionsPopup? by mutableStateOf(null)
        internal set

    /** The document whose "3 usages" hint is under the mouse (the pointer is a hand over it), null when none. */
    var hintUnderMouse: Document? by mutableStateOf(null)

    /** The ranges Ctrl+W walks through, from the last answer of the server (see `SmartSelection.kt`). */
    internal var selectionSteps: SelectionSteps? = null

    /** The message shown, null when none. */
    var notice: InsightNotice? by mutableStateOf(null)
        private set

    /** The word underlined under the mouse while Ctrl is held. */
    var link: LinkTarget? by mutableStateOf(null)
        private set

    /** Whether the mouse is over the documentation popup: it stays while it is. */
    var hoverPopupHovered = false
        private set

    private val insights = HashMap<Document, DocumentInsight>()
    private var hoverJob: Job? = null
    private var hideJob: Job? = null
    private var hoverWord: Pair<Document, TextRange>? = null
    private var highlightJob: Job? = null
    private var signatureJob: Job? = null
    private var noticeJob: Job? = null
    private var lastDiagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot.Empty

    /** What the language server said about [document], null when it has none. */
    fun insightOf(document: Document): DocumentInsight? = insights[document]

    /** The documents open with a language server, by path: new ones are followed, closed ones forgotten. */
    fun track(documents: Map<String, Document>) {
        val wanted = documents.entries.associate { (path, document) -> document to path }
        val gone = insights.keys.filter { it !in wanted }
        for (document in gone) forget(document)
        for ((document, path) in wanted) {
            val existing = insights[document]
            if (existing == null) follow(document, path) else existing.path = path
        }
        applyDiagnostics(lastDiagnostics)
    }

    private fun follow(document: Document, path: String) {
        val insight = DocumentInsight(document).also { it.path = path }
        insights[document] = insight
        document.markerListener = { change -> onEdit(insight, change) }
        refreshUsages(insight, delayed = false)
    }

    private fun forget(document: Document) {
        insights.remove(document)?.usagesJob?.cancel()
        document.markerListener = null
    }

    /** The server sent newer diagnostics. */
    fun applyDiagnostics(snapshot: DiagnosticsSnapshot) {
        lastDiagnostics = snapshot
        val byPath = snapshot.files.associate { normalized(it.path) to it.diagnostics }
        for (insight in insights.values) {
            val diagnostics = byPath[normalized(insight.path)]
            if (diagnostics != null) insight.diagnostics = clamp(diagnostics, insight.document)
        }
    }

    /** The language server restarted, or another one serves the files: the usage counts are asked again. */
    fun refreshAll() = insights.values.forEach { refreshUsages(it, delayed = false) }

    private fun onEdit(insight: DocumentInsight, change: TextChange) {
        insight.follow(change)
        refreshUsages(insight, delayed = true)
        dismissHover()
        notice = null
        if (codeActions?.document === insight.document) codeActions = null
        if (locations?.document === insight.document) locations = null
    }

    // The usage counts are asked once typing pauses, and again while the server is not ready.
    private fun refreshUsages(insight: DocumentInsight, delayed: Boolean) {
        insight.usagesJob?.cancel()
        insight.usagesJob =
            scope.launch {
                if (delayed) delay(USAGES_DELAY_MS)
                countUsages(insight)
                foldingFromServer(insight)
            }
    }

    private suspend fun countUsages(insight: DocumentInsight) {
        var attempts = 0
        var settled = false
        while (!settled && attempts < NOT_READY_RETRIES) {
            attempts++
            val version = insight.document.version
            val result =
                askVersion(
                    insight.path,
                    insight.document,
                ) { servers, at -> servers.usageCounts(insight.path, at) }
            val current = insight.document.version == version
            val status = result?.status.takeIf { current }
            if (status == FeatureStatus.Ready) insight.usageCounts = result?.counts.orEmpty()
            settled = status == FeatureStatus.Ready || status == FeatureStatus.Unsupported
            if (!settled) delay(NOT_READY_RETRY_MS)
        }
    }

    // The regions only the server knows how to fold, once it counted the usages of the same text.
    private suspend fun foldingFromServer(insight: DocumentInsight) {
        val document = insight.document
        val version = document.version
        val result = askVersion(insight.path, document) { servers, at -> servers.foldingRanges(insight.path, at) }
        val ready = result?.takeIf { it.status == FeatureStatus.Ready && document.version == version }
        ready?.let { document.folding.serverRegions(it.regions, document.lineCount) }
    }

    // --- Hover

    /**
     * The mouse rests at [pos] of [document] ([pos] null: not over a word). The documentation comes after a short
     * pause; with [ctrl], the word is underlined like a link.
     */
    fun mouseAt(path: String, document: Document, pos: TextPos?, ctrl: Boolean) {
        val word = pos?.let { wordRange(document, it) }
        val linked = word?.takeIf { ctrl }
        link = linked?.let { LinkTarget(document, it.startLine, it.startColumn, it.endColumn) }
        val previous = hoverWord
        val sameWord = word != null && previous?.first === document && previous.second == word
        if (sameWord) return
        hoverJob?.cancel()
        hoverWord = word?.let { document to it }
        // The documentation of the previous word goes, unless the mouse goes onto it.
        scheduleHide()
        if (word != null) {
            hoverJob =
                scope.launch {
                    delay(HOVER_DELAY_MS)
                    showHover(path, document, pos, TextPos(word.startLine, word.startColumn))
                }
        }
    }

    /** The mouse left the code: the documentation goes, unless the mouse went onto it. */
    fun mouseLeft() {
        hoverJob?.cancel()
        hoverWord = null
        link = null
        scheduleHide()
    }

    /** The mouse entered ([inside]) or left the documentation popup. */
    fun hoverPopupHovered(inside: Boolean) {
        hoverPopupHovered = inside
        if (inside) hideJob?.cancel() else scheduleHide()
    }

    /** Ctrl was released: no link any more. */
    fun ctrlReleased() {
        link = null
    }

    /** Ctrl+Q: the documentation of the symbol at the caret. */
    fun quickDocumentation(path: String, document: Document) {
        val caret = document.caret
        val before = TextPos(caret.line, (caret.column - 1).coerceAtLeast(0))
        val word = wordRange(document, caret) ?: wordRange(document, before)
        val anchor = word?.let { TextPos(it.startLine, it.startColumn) } ?: caret
        hoverJob?.cancel()
        hoverJob = scope.launch { showHover(path, document, caret, anchor, explicit = true) }
    }

    private suspend fun showHover(
        path: String,
        document: Document,
        pos: TextPos,
        anchor: TextPos,
        explicit: Boolean = false,
    ) {
        val version = document.version
        val problems = insights[document]?.diagnostics.orEmpty().filter { inside(pos, it.range) }
        val result = askAt(path, document, pos) { servers, at -> servers.hover(at) }
        val markdown = result?.markdown
        val nothing = markdown == null && problems.isEmpty()
        when {
            document.version != version -> {
                // the text changed meanwhile: the answer is about an older one
            }

            nothing && explicit -> {
                notify(document, anchor, "No documentation found")
            }

            nothing -> {
                // a word without documentation shows nothing on hover
            }

            else -> {
                hideJob?.cancel()
                hover = HoverPopup(document, anchor, markdown, problems)
            }
        }
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        if (hover != null) {
            hideJob =
                scope.launch {
                    delay(HOVER_HIDE_MS)
                    if (!hoverPopupHovered) hover = null
                }
        }
    }

    /** Closes the documentation. */
    fun dismissHover() {
        hoverJob?.cancel()
        hideJob?.cancel()
        hoverWord = null
        hoverPopupHovered = false
        hover = null
    }

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
        dismissHover()
        link = null
        val request = NavigationRequest(path, document, pos, kind, wordRange(document, pos))
        scope.launch {
            val version = document.version
            val result =
                askAt(
                    path,
                    document,
                    pos,
                ) { servers, at -> servers.navigate(at, kind) } ?: LocationsResult.NotReady
            if (document.version == version) navigated(request, result)
        }
    }

    private fun navigated(request: NavigationRequest, result: LocationsResult) {
        val document = request.document
        val anchor = request.anchor
        val ready = result.status == FeatureStatus.Ready
        // Ctrl+B without a language server: by name, as without one.
        val byName = !ready && request.kind == NavigationKind.Definition
        when {
            byName -> {
                host.declarationByName(request.pos)
            }

            result.status == FeatureStatus.Unsupported -> {
                notify(
                    document,
                    anchor,
                    "The language server cannot find ${what(request.kind)}",
                )
            }

            result.status == FeatureStatus.NotReady -> {
                notify(document, anchor, NOT_READY)
            }

            result.locations.isEmpty() -> {
                notify(document, anchor, emptyMessage(request.kind))
            }

            else -> {
                arrived(request, result.locations)
            }
        }
    }

    private fun arrived(request: NavigationRequest, locations: List<SourceLocation>) {
        val name = request.name
        val single = locations.singleOrNull()
        val landing = single?.let { landingOf(it, name) }
        val here = single != null && sameFile(single.path, request.path)
        val onItself = here && landing != null && isAt(request.pos, landing, name)
        when {
            onItself && request.kind == NavigationKind.Definition -> {
                showUsages(
                    request.path,
                    request.document,
                    request.pos,
                )
            }

            single != null && landing != null -> {
                host.navigate(single.path, landing)
            }

            else -> {
                showLocations(listTitle(request.kind, name), locations, request.document, request.anchor)
            }
        }
    }

    // A definition range may cover the whole declaration (phpactor): the caret goes to the name inside it.
    private fun landingOf(location: SourceLocation, name: String?): TextPos {
        val range = location.range
        val preview = location.preview
        val from = range.startColumn.coerceAtMost(preview?.length ?: 0)
        val column = if (name != null && preview != null) preview.indexOf(name, from) else -1
        return if (column >= 0) TextPos(range.startLine, column) else TextPos(range.startLine, range.startColumn)
    }

    private fun isAt(pos: TextPos, landing: TextPos, name: String?): Boolean {
        val length = name?.length ?: 0
        return pos.line == landing.line && pos.column in landing.column..landing.column + length
    }

    /** Alt+F7, or a click on a "3 usages" hint: the usages of the symbol at [pos]; one usage is opened directly. */
    fun showUsages(path: String, document: Document, pos: TextPos) {
        dismissHover()
        val word = wordRange(document, pos)
        val anchor = word?.let { TextPos(it.startLine, it.startColumn) } ?: pos
        val name = word?.let { document.lines[it.startLine].substring(it.startColumn, it.endColumn) }
        scope.launch {
            val version = document.version
            val result =
                askAt(
                    path,
                    document,
                    pos,
                ) { servers, at -> servers.references(at, false) } ?: LocationsResult.NotReady
            val message =
                when {
                    result.status == FeatureStatus.NotReady -> NOT_READY
                    result.status == FeatureStatus.Unsupported -> "The language server cannot find usages"
                    result.locations.isEmpty() -> "No usages found"
                    else -> null
                }
            when {
                document.version != version -> Unit
                message != null -> notify(document, anchor, message)
                else -> showLocations(usagesTitle(name, result), result.locations, document, anchor)
            }
        }
    }

    private fun usagesTitle(name: String?, result: LocationsResult): String {
        val count = result.locations.size
        val more = if (result.truncated) "+" else ""
        val usages = if (count == 1) "1 usage" else "$count$more usages"
        return if (name != null) "Usages of $name  $usages" else usages
    }

    private fun showLocations(title: String, locations: List<SourceLocation>, document: Document, anchor: TextPos) {
        val names = locations.map { host.relativePath(it.path) }
        this.locations = LocationsPopup(title, locations, document, anchor, names)
    }

    /** Moves the selection of the list of places or of quick fixes by [delta], wrapping around. */
    fun moveSelection(delta: Int) {
        locations?.let { popup -> popup.selected = (popup.selected + delta).mod(popup.locations.size) }
        codeActions?.let { popup -> popup.selected = (popup.selected + delta).mod(popup.actions.size.coerceAtLeast(1)) }
    }

    /** Opens the place [index] of the list, or the selected one. */
    fun openLocation(index: Int? = null) {
        val popup = locations ?: return
        val location = popup.locations.getOrNull(index ?: popup.selected) ?: return
        locations = null
        host.navigate(location.path, TextPos(location.range.startLine, location.range.startColumn))
    }

    /** Closes the list of places. */
    fun dismissLocations() {
        locations = null
    }

    // --- Highlights and parameter info

    /** The caret of [document] moved or its text changed: the occurrences of the symbol there are asked again. */
    fun caretMoved(path: String, document: Document) {
        val insight = insights[document] ?: return
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
        if (signature?.document === document) requestSignature(path, document, explicit = false)
    }

    /** Text was typed: `(` and `, ` open the parameter info, which follows the caret while it is shown. */
    fun edited(path: String, document: Document) {
        val caret = document.caret
        val before = document.lines.getOrNull(caret.line)?.getOrNull(caret.column - 1)
        val opens = before == '(' || before == ','
        if (opens || signature?.document === document) requestSignature(path, document, explicit = false)
    }

    /** Ctrl+P: the parameter info at the caret. */
    fun parameterInfo(path: String, document: Document) = requestSignature(path, document, explicit = true)

    private fun requestSignature(path: String, document: Document, explicit: Boolean) {
        signatureJob?.cancel()
        val caret = document.caret
        val version = document.version
        signatureJob =
            scope.launch {
                if (!explicit) delay(SIGNATURE_DELAY_MS)
                val help = askAt(path, document, caret) { servers, at -> servers.signatureHelp(at) }
                val unchanged = document.version == version && document.caret == caret
                val shown = help?.takeIf { it.signatures.isNotEmpty() }
                if (unchanged) {
                    signature = shown?.let { SignaturePopup(document, caret, it) }
                    if (shown == null && explicit) notify(document, caret, "No parameters to show")
                }
            }
    }

    /** Closes the parameter info. */
    fun dismissSignature() {
        signatureJob?.cancel()
        signature = null
    }

    // --- Problems

    /** F2 (Shift+F2 with [backward]): moves the caret to the next problem of [document], wrapping around. */
    fun nextProblem(document: Document, backward: Boolean = false) {
        val problems = insights[document]?.diagnostics.orEmpty()
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
    fun dismissNotice() {
        noticeJob?.cancel()
        notice = null
    }

    /** Escape: closes what is shown over the code; false when nothing was. */
    fun escape(): Boolean {
        val shown = listOf(hover, locations, signature, codeActions, notice, rename).any { it != null }
        dismissHover()
        locations = null
        dismissSignature()
        codeActions = null
        rename = null
        dismissNotice()
        return shown
    }

    internal fun notify(document: Document, anchor: TextPos, text: String) {
        noticeJob?.cancel()
        notice = InsightNotice(document, anchor, text)
        noticeJob =
            scope.launch {
                delay(NOTICE_MS)
                notice = null
            }
    }

    // Null when there is no backend or the link failed: the caller shows "not ready".
    private suspend fun <T> ask(request: suspend (InsightBackend) -> T?): T? {
        val current = backend() ?: return null
        return try {
            request(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IOException) {
            null
        }
    }

    /** Asks about [pos] of [document], as the server has it; null when it does not mirror the document. */
    internal suspend fun <T> askAt(
        path: String,
        document: Document,
        pos: TextPos,
        request: suspend (CodeIntelligence, DocumentPosition) -> T?,
    ): T? =
        ask { current ->
            val version = current.versionOf(path, document)
            version?.let { request(current.intelligence, DocumentPosition(path, it, pos.line, pos.column)) }
        }

    /** Asks about the version of [document] the server has; null when it does not mirror the document. */
    internal suspend fun <T> askVersion(
        path: String,
        document: Document,
        request: suspend (CodeIntelligence, Int) -> T?,
    ): T? = ask { current -> current.versionOf(path, document)?.let { request(current.intelligence, it) } }

    /** Asks the language servers something that is about no document. */
    internal suspend fun <T> askServers(request: suspend (CodeIntelligence) -> T?): T? =
        ask { current -> request(current.intelligence) }

    private fun what(kind: NavigationKind) =
        when (kind) {
            NavigationKind.Definition -> "declarations"
            NavigationKind.TypeDefinition -> "type declarations"
            NavigationKind.Implementation -> "implementations"
        }

    private fun emptyMessage(kind: NavigationKind) =
        when (kind) {
            NavigationKind.Definition -> "Cannot find declaration to go to"
            NavigationKind.TypeDefinition -> "Cannot find type declaration to go to"
            NavigationKind.Implementation -> "No implementations found"
        }

    private fun listTitle(kind: NavigationKind, name: String?): String {
        val of = name?.let { " of $it" }.orEmpty()
        return when (kind) {
            NavigationKind.Definition -> "Choose declaration$of"
            NavigationKind.TypeDefinition -> "Choose type declaration$of"
            NavigationKind.Implementation -> "Choose implementation$of"
        }
    }
}
