package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CodeActionInfo
import org.example.kotlineditor.model.CodeIntelligence
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.LocationsResult
import org.example.kotlineditor.model.NavigationKind
import org.example.kotlineditor.model.SignatureHelpResult
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.SymbolHighlight
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.model.UsageCount
import java.io.IOException

private const val HOVER_DELAY_MS = 500L
private const val HOVER_HIDE_MS = 300L
private const val HIGHLIGHT_DELAY_MS = 250L
private const val USAGES_DELAY_MS = 800L
private const val NOT_READY_RETRY_MS = 1_000L
private const val NOT_READY_RETRIES = 30
private const val NOTICE_MS = 3_000L
private const val SIGNATURE_DELAY_MS = 150L

/** What the code intelligence of the editor asks the language servers through. */
interface InsightBackend {
    /** The language servers' requests. */
    val intelligence: CodeIntelligence

    /** Version of [path] the server has once what is typed is sent; null when no language server mirrors it. */
    fun versionOf(path: String, document: Document): Int?
}

/** What the code intelligence does outside of the editor: open files, apply edits, the old declaration search. */
interface InsightHost {
    /** Opens [path] with the caret at [start], remembering where we came from (Ctrl+Alt+Left). */
    fun navigate(path: String, start: TextPos)

    /** Applies the edits of a rename, a formatting or a quick fix to the open documents and the files; an error. */
    suspend fun apply(edit: EditResult): String?

    /** Ctrl+B without a ready language server: the declaration found by name in the project. */
    fun declarationByName(at: TextPos)

    /** Path of [path] relative to the project, for the lists of places. */
    fun relativePath(path: String): String
}

/** The problems, usage counts and occurrences the language server gave for one document; they follow its edits. */
@Stable
class DocumentInsight internal constructor(val document: Document) {
    /** The problems, in document order. */
    var diagnostics: List<Diagnostic> by mutableStateOf(emptyList())
        internal set

    /** The usage count of each declaration. */
    var usageCounts: List<UsageCount> by mutableStateOf(emptyList())
        internal set

    /** The occurrences of the symbol at the caret, empty while the caret moves or after an edit. */
    var highlights: List<SymbolHighlight> by mutableStateOf(emptyList())
        internal set

    internal var path: String = ""
    internal var usagesJob: Job? = null

    /** Errors among [diagnostics]. */
    val errorCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.Error }

    /** Warnings among [diagnostics]. */
    val warningCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.Warning }

    internal fun follow(change: TextChange) {
        diagnostics = diagnostics.mapNotNull { diagnostic -> shifted(diagnostic.range, change)?.let { diagnostic.copy(range = it) } }
        usageCounts = usageCounts.mapNotNull { count -> shifted(count.range, change)?.let { count.copy(range = it) } }
        highlights = emptyList()
    }
}

/** The documentation shown over the code: under the mouse, or at the caret with Ctrl+Q. */
@Immutable
class HoverPopup(
    /** The document it documents. */
    val document: Document,
    /** Start of the word it documents: the popup goes under it. */
    val anchor: TextPos,
    /** The documentation, in Markdown; null when only problems are shown. */
    val markdown: String?,
    /** The problems at that place. */
    val problems: List<Diagnostic>,
)

/** A list of places to go to: the usages of a symbol, or its declarations when there are several. */
@Stable
class LocationsPopup(
    /** Title of the list (`Usages of greet`). */
    val title: String,
    /** The places. */
    val locations: List<SourceLocation>,
    /** The document it was asked from. */
    val document: Document,
    /** Where it was asked: the list goes under it. */
    val anchor: TextPos,
    /** Relative paths of the places, for their rows. */
    val names: List<String>,
) {
    /** The selected row. */
    var selected by mutableIntStateOf(0)
        internal set
}

/** The signature of the call around the caret (Ctrl+P, or typed `(` and `,`). */
@Immutable
class SignaturePopup(
    /** The document. */
    val document: Document,
    /** The caret when it was asked: the popup goes above it. */
    val anchor: TextPos,
    /** What the server answered. */
    val help: SignatureHelpResult,
)

/** The name being typed for a rename (Shift+F6). */
@Stable
class RenamePopup(
    /** The document. */
    val document: Document,
    /** Its path. */
    val path: String,
    /** The range of the renamed name. */
    val range: TextRange,
    /** The current name. */
    val oldName: String,
) {
    /** The new name, as typed. */
    var name by mutableStateOf(oldName)

    /** The rename is being applied. */
    var applying by mutableStateOf(false)
        internal set
}

/** The quick fixes and refactorings offered at the caret (Alt+Enter). */
@Stable
class CodeActionsPopup(
    /** The document. */
    val document: Document,
    /** Where they apply: the list goes under it. */
    val anchor: TextPos,
    /** Identifies the list on the server. */
    val token: String,
    /** The actions. */
    val actions: List<CodeActionInfo>,
) {
    /** The selected row. */
    var selected by mutableIntStateOf(0)
        internal set
}

/** A short message over the code (`No usages found`), gone after a few seconds or at the next keystroke. */
@Immutable
class InsightNotice(val document: Document, val anchor: TextPos, val text: String)

/** The word under the mouse with Ctrl held: underlined like a link, Ctrl+click goes to its declaration. */
@Immutable
class LinkTarget(val document: Document, val line: Int, val start: Int, val end: Int)

/**
 * Code intelligence in the editor (see #97), like IntelliJ's: documentation on hover and Ctrl+Q, Ctrl+click and
 * Ctrl+B to the declaration (its usages when already on it), Ctrl+Alt+B and Ctrl+Shift+B, usages with Alt+F7 and
 * a "3 usages" hint after each declaration, problems underlined, occurrences of the symbol at the caret, parameter
 * info, rename (Shift+F6), reformat (Ctrl+Alt+L) and quick fixes (Alt+Enter). Each request is about the version of
 * the document it was made on: an answer for an older text, or after the user moved on, is dropped.
 */
@Stable
@Suppress("TooManyFunctions")
class CodeInsightState(
    private val scope: CoroutineScope,
    private val backend: () -> InsightBackend?,
    private val host: InsightHost,
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
        private set

    /** The quick fixes shown, null when none. */
    var codeActions: CodeActionsPopup? by mutableStateOf(null)
        private set

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
        for ((document, insight) in insights.entries.toList()) {
            if (document !in wanted) {
                insights.remove(document)
                insight.usagesJob?.cancel()
                if (document.markerListener != null) document.markerListener = null
            }
        }
        for ((document, path) in wanted) {
            val existing = insights[document]
            if (existing == null) {
                val insight = DocumentInsight(document).also { it.path = path }
                insights[document] = insight
                document.markerListener = { change -> onEdit(insight, change) }
                refreshUsages(insight, delayed = false)
            } else {
                existing.path = path
            }
        }
        applyDiagnostics(lastDiagnostics)
    }

    /** The server sent newer diagnostics. */
    fun applyDiagnostics(snapshot: DiagnosticsSnapshot) {
        lastDiagnostics = snapshot
        val byPath = snapshot.files.associate { normalized(it.path) to it.diagnostics }
        for (insight in insights.values) {
            val diagnostics = byPath[normalized(insight.path)] ?: continue
            insight.diagnostics = clamp(diagnostics, insight.document)
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
                repeat(NOT_READY_RETRIES) {
                    val version = insight.document.version
                    val result = ask { backend -> backend.intelligence.usageCounts(insight.path, backend.versionOf(insight.path, insight.document) ?: return@ask null) }
                    when {
                        result == null || insight.document.version != version -> Unit
                        result.status == FeatureStatus.Ready -> {
                            insight.usageCounts = result.counts
                            return@launch
                        }

                        result.status == FeatureStatus.Unsupported -> return@launch
                    }
                    delay(NOT_READY_RETRY_MS)
                }
            }
    }

    // --- Hover

    /**
     * The mouse rests at [pos] of [document] ([pos] null: not over a word). The documentation comes after a short
     * pause; with [ctrl], the word is underlined like a link.
     */
    fun mouseAt(path: String, document: Document, pos: TextPos?, ctrl: Boolean) {
        val word = pos?.let { wordRange(document, it) }
        link = if (ctrl && word != null) LinkTarget(document, word.startLine, word.startColumn, word.endColumn) else null
        val sameWord = word != null && hoverWord?.first === document && hoverWord?.second == word
        if (sameWord) return
        hoverJob?.cancel()
        hoverWord = word?.let { document to it }
        if (word == null || pos == null) {
            scheduleHide()
            return
        }
        hoverJob =
            scope.launch {
                delay(HOVER_DELAY_MS)
                showHover(path, document, pos, TextPos(word.startLine, word.startColumn))
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
        val word = wordRange(document, caret) ?: wordRange(document, TextPos(caret.line, (caret.column - 1).coerceAtLeast(0)))
        val anchor = word?.let { TextPos(it.startLine, it.startColumn) } ?: caret
        hoverJob?.cancel()
        hoverJob = scope.launch { showHover(path, document, caret, anchor, explicit = true) }
    }

    private suspend fun showHover(path: String, document: Document, pos: TextPos, anchor: TextPos, explicit: Boolean = false) {
        val version = document.version
        val problems = insights[document]?.diagnostics.orEmpty().filter { inside(pos, it.range) }
        val result = ask { backend -> backend.intelligence.hover(position(backend, path, document, pos) ?: return@ask null) }
        if (document.version != version) return
        val markdown = result?.markdown
        if (markdown == null && problems.isEmpty()) {
            if (explicit) notify(document, anchor, "No documentation found")
            return
        }
        hideJob?.cancel()
        hover = HoverPopup(document, anchor, markdown, problems)
    }

    private fun scheduleHide() {
        hideJob?.cancel()
        if (hover == null) return
        hideJob =
            scope.launch {
                delay(HOVER_HIDE_MS)
                if (!hoverPopupHovered) hover = null
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
        navigation(path, document, pos, NavigationKind.Definition, fallback = true)

    /** Ctrl+Shift+B: the declaration of the type of the symbol at the caret. */
    fun gotoTypeDeclaration(path: String, document: Document) =
        navigation(path, document, document.caret, NavigationKind.TypeDefinition, fallback = false)

    /** Ctrl+Alt+B: the implementations of the symbol at the caret. */
    fun gotoImplementation(path: String, document: Document) =
        navigation(path, document, document.caret, NavigationKind.Implementation, fallback = false)

    private fun navigation(path: String, document: Document, pos: TextPos, kind: NavigationKind, fallback: Boolean) {
        dismissHover()
        link = null
        val word = wordRange(document, pos)
        val anchor = word?.let { TextPos(it.startLine, it.startColumn) } ?: pos
        scope.launch {
            val version = document.version
            val result =
                ask { backend -> backend.intelligence.navigate(position(backend, path, document, pos) ?: return@ask null, kind) }
                    ?: LocationsResult.NotReady
            if (document.version != version) return@launch
            val name = word?.let { document.lines[it.startLine].substring(it.startColumn, it.endColumn) }
            when {
                result.status != FeatureStatus.Ready && fallback -> host.declarationByName(pos)
                result.status == FeatureStatus.Unsupported -> notify(document, anchor, "The language server cannot find ${what(kind)}")
                result.status == FeatureStatus.NotReady -> notify(document, anchor, "The language server is not ready")
                result.locations.isEmpty() -> notify(document, anchor, emptyMessage(kind))
                else -> arrived(path, document, pos, anchor, kind, name, result.locations)
            }
        }
    }

    @Suppress("LongParameterList")
    private fun arrived(
        path: String,
        document: Document,
        pos: TextPos,
        anchor: TextPos,
        kind: NavigationKind,
        name: String?,
        locations: List<SourceLocation>,
    ) {
        val single = locations.singleOrNull()
        val landing = single?.let { landingOf(it, name) }
        val onItself = single != null && landing != null && sameFile(single.path, path) && isAt(pos, landing, name)
        when {
            onItself && kind == NavigationKind.Definition -> showUsages(path, document, pos)
            single != null && landing != null -> host.navigate(single.path, landing)
            else -> showLocations(listTitle(kind, name), locations, document, anchor)
        }
    }

    // A definition range may cover the whole declaration (phpactor): the caret goes to the name inside it.
    private fun landingOf(location: SourceLocation, name: String?): TextPos {
        val range = location.range
        val preview = location.preview
        val column = if (name != null && preview != null) preview.indexOf(name, range.startColumn.coerceAtMost(preview.length)) else -1
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
                ask { backend -> backend.intelligence.references(position(backend, path, document, pos) ?: return@ask null, false) }
                    ?: LocationsResult.NotReady
            if (document.version != version) return@launch
            when {
                result.status == FeatureStatus.NotReady -> notify(document, anchor, "The language server is not ready")
                result.status == FeatureStatus.Unsupported -> notify(document, anchor, "The language server cannot find usages")
                result.locations.isEmpty() -> notify(document, anchor, "No usages found")
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
        if (document.hasSelection) return
        val caret = document.caret
        val version = document.version
        highlightJob =
            scope.launch {
                delay(HIGHLIGHT_DELAY_MS)
                if (wordRange(document, caret) == null) return@launch
                val found = ask { backend -> backend.intelligence.highlights(position(backend, path, document, caret) ?: return@ask null) }
                val unchanged = document.version == version && document.caret == caret
                // A lone occurrence is not worth highlighting, like IntelliJ.
                if (found != null && unchanged && found.size > 1) insight.highlights = found
            }
        val shown = signature
        if (shown != null && shown.document === document) requestSignature(path, document, explicit = false)
    }

    /** Text was typed: `(` and `,` open the parameter info, which follows the caret while it is shown. */
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
                val help = ask { backend -> backend.intelligence.signatureHelp(position(backend, path, document, caret) ?: return@ask null) }
                if (document.version != version || document.caret != caret) return@launch
                val shown = help != null && help.signatures.isNotEmpty()
                signature = if (shown) SignaturePopup(document, caret, help!!) else null
                if (!shown && explicit) notify(document, caret, "No parameters to show")
            }
    }

    /** Closes the parameter info. */
    fun dismissSignature() {
        signatureJob?.cancel()
        signature = null
    }

    // --- Rename, reformat, quick fixes

    /** Shift+F6: asks what can be renamed at the caret, then shows the rename popup with its name. */
    fun startRename(path: String, document: Document) {
        dismissHover()
        val caret = document.caret
        scope.launch {
            val version = document.version
            val target = ask { backend -> backend.intelligence.prepareRename(position(backend, path, document, caret) ?: return@ask null) }
            if (document.version != version) return@launch
            val range = target?.range
            val name = target?.placeholder ?: range?.let { textOf(document, it) }
            when {
                target == null || target.status == FeatureStatus.NotReady -> notify(document, caret, "The language server is not ready")
                target.status == FeatureStatus.Unsupported -> notify(document, caret, "The language server cannot rename")
                range == null || name == null -> notify(document, caret, target.message ?: "Cannot rename this element")
                else -> rename = RenamePopup(document, path, range, name)
            }
        }
    }

    /** Enter in the rename popup: renames everywhere, in the open documents and the files. */
    fun confirmRename() {
        val popup = rename ?: return
        val newName = popup.name.trim()
        if (newName.isEmpty() || newName == popup.oldName) {
            rename = null
            return
        }
        popup.applying = true
        val anchor = TextPos(popup.range.startLine, popup.range.startColumn)
        scope.launch {
            val result =
                ask { backend ->
                    val at = position(backend, popup.path, popup.document, anchor) ?: return@ask null
                    backend.intelligence.rename(at, newName)
                } ?: EditResult.NotReady
            rename = null
            applyResult(popup.document, anchor, result, "rename")
        }
    }

    /** Escape in the rename popup. */
    fun cancelRename() {
        rename = null
    }

    /** Ctrl+Alt+L: reformats the selection, or the whole document. */
    fun reformat(path: String, document: Document) {
        val selection = document.selectionRange()
        val range = selection?.let { (start, end) -> TextRange(start.line, start.column, end.line, end.column) }
        val anchor = document.caret
        scope.launch {
            val version = document.version
            val result =
                ask { backend ->
                    val serverVersion = backend.versionOf(path, document) ?: return@ask null
                    backend.intelligence.format(path, serverVersion, range, FormatOptions())
                } ?: EditResult.NotReady
            if (document.version != version) return@launch
            applyResult(document, anchor, result, "reformat")
        }
    }

    /** Alt+Enter: the quick fixes and refactorings at the caret, or the selection. */
    fun showCodeActions(path: String, document: Document) {
        dismissHover()
        val caret = document.caret
        val selection = document.selectionRange()
        val range = selection?.let { (start, end) -> TextRange(start.line, start.column, end.line, end.column) } ?: TextRange(caret.line, caret.column, caret.line, caret.column)
        scope.launch {
            val version = document.version
            val result =
                ask { backend ->
                    val serverVersion = backend.versionOf(path, document) ?: return@ask null
                    backend.intelligence.codeActions(path, serverVersion, range)
                }
            if (document.version != version) return@launch
            when {
                result == null || result.status == FeatureStatus.NotReady -> notify(document, caret, "The language server is not ready")
                result.status == FeatureStatus.Unsupported || result.actions.isEmpty() -> notify(document, caret, "No actions available here")
                else -> codeActions = CodeActionsPopup(document, caret, result.token, result.actions)
            }
        }
    }

    /** Applies the quick fix [index] of the list, or the selected one. */
    fun applyCodeAction(index: Int? = null) {
        val popup = codeActions ?: return
        val chosen = index ?: popup.selected
        if (popup.actions.getOrNull(chosen) == null) return
        codeActions = null
        scope.launch {
            val result = ask { backend -> backend.intelligence.applyCodeAction(popup.token, chosen) } ?: EditResult.NotReady
            applyResult(popup.document, popup.anchor, result, "apply the action")
        }
    }

    /** Closes the quick fixes. */
    fun dismissCodeActions() {
        codeActions = null
    }

    private suspend fun applyResult(document: Document, anchor: TextPos, result: EditResult, what: String) {
        val failure =
            when {
                result.status == FeatureStatus.NotReady -> "The language server is not ready"
                result.status == FeatureStatus.Unsupported -> "The language server cannot $what"
                result.message != null -> result.message
                else -> host.apply(result)
            }
        failure?.let { notify(document, anchor, it) }
    }

    // --- Problems

    /** F2 (Shift+F2 with [backward]): moves the caret to the next problem of [document], wrapping around. */
    fun nextProblem(document: Document, backward: Boolean = false) {
        val problems = insights[document]?.diagnostics.orEmpty()
        if (problems.isEmpty()) return
        val caret = document.caret
        val starts = problems.map { TextPos(it.range.startLine, it.range.startColumn) }.distinct().sorted()
        val target =
            if (backward) {
                starts.lastOrNull { it < caret } ?: starts.last()
            } else {
                starts.firstOrNull { it > caret } ?: starts.first()
            }
        document.moveTo(target)
    }

    /** Closes the message. */
    fun dismissNotice() {
        noticeJob?.cancel()
        notice = null
    }

    /** Escape: closes what is shown over the code; false when nothing was. */
    fun escape(): Boolean {
        val shown = hover != null || locations != null || signature != null || codeActions != null || notice != null || rename != null
        dismissHover()
        locations = null
        dismissSignature()
        codeActions = null
        rename = null
        dismissNotice()
        return shown
    }

    private fun notify(document: Document, anchor: TextPos, text: String) {
        noticeJob?.cancel()
        notice = InsightNotice(document, anchor, text)
        noticeJob =
            scope.launch {
                delay(NOTICE_MS)
                notice = null
            }
    }

    // Asks the backend; null when there is none or the link failed, so that the caller shows "not ready".
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

    private fun position(backend: InsightBackend, path: String, document: Document, pos: TextPos): DocumentPosition? {
        val version = backend.versionOf(path, document) ?: return null
        return DocumentPosition(path, version, pos.line, pos.column)
    }

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

/** The identifier at [pos] of [document] (PHP's `$` included), null when there is none. */
fun wordRange(document: Document, pos: TextPos): TextRange? {
    val text = document.lines.getOrNull(pos.line) ?: return null
    var start = pos.column.coerceIn(0, text.length)
    var end = start
    while (start > 0 && isIdentifierChar(text[start - 1])) start--
    while (end < text.length && isIdentifierChar(text[end])) end++
    val word = text.substring(start, end)
    val isIdentifier = word.isNotEmpty() && word.any { it.isLetter() || it == '_' }
    return if (isIdentifier) TextRange(pos.line, start, pos.line, end) else null
}

private fun isIdentifierChar(c: Char) = c.isLetterOrDigit() || c == '_' || c == '$'

private fun inside(pos: TextPos, range: TextRange): Boolean {
    val start = TextPos(range.startLine, range.startColumn)
    val end = TextPos(range.endLine, range.endColumn)
    return pos >= start && pos <= end
}

private fun textOf(document: Document, range: TextRange): String? {
    if (range.startLine != range.endLine) return null
    val line = document.lines.getOrNull(range.startLine) ?: return null
    val start = range.startColumn.coerceIn(0, line.length)
    val end = range.endColumn.coerceIn(start, line.length)
    return line.substring(start, end).takeIf { it.isNotEmpty() }
}

// Ranges past the end of the text (the server computed them on a longer one) are cut to it.
private fun clamp(diagnostics: List<Diagnostic>, document: Document): List<Diagnostic> {
    val lastLine = document.lines.lastIndex
    return diagnostics.mapNotNull { diagnostic ->
        val range = diagnostic.range
        if (range.startLine > lastLine) return@mapNotNull null
        val endLine = range.endLine.coerceAtMost(lastLine)
        val startColumn = range.startColumn.coerceIn(0, document.lines[range.startLine].length)
        val endColumn = range.endColumn.coerceIn(0, document.lines[endLine].length)
        diagnostic.copy(range = TextRange(range.startLine, startColumn, endLine, endColumn))
    }
}

/** [path] with `/` separators and no trailing one, to compare the paths the server and the client name. */
fun normalized(path: String): String = path.replace('\\', '/').trimEnd('/')

private fun sameFile(first: String, second: String) = normalized(first) == normalized(second)
