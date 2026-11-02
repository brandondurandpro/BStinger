package org.example.kotlineditor.state

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Job
import org.example.kotlineditor.model.CodeActionInfo
import org.example.kotlineditor.model.CodeIntelligence
import org.example.kotlineditor.model.CodeLensInfo
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.model.SemanticToken
import org.example.kotlineditor.model.SignatureHelpResult
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.SymbolHighlight
import org.example.kotlineditor.model.TextChange
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.model.UsageCount

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

    /** Ctrl+B without a ready language server: goes to the declaration found by name in the project; false if none. */
    suspend fun declarationByName(at: TextPos): Boolean

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

    /** The code lenses (`2 implementations`, `Run test`), in document order. */
    var codeLenses: List<CodeLensInfo> by mutableStateOf(emptyList())
        internal set

    /** The semantic tokens (what each word is), in the order of the text. */
    var semanticTokens: List<SemanticToken> by mutableStateOf(emptyList())
        internal set

    /** The inlay hints (`name:` before an argument), in the order of the text. */
    var inlayHints: List<InlayHint> by mutableStateOf(emptyList())
        internal set

    /** The occurrences of the symbol at the caret, empty while the caret moves or after an edit. */
    var highlights: List<SymbolHighlight> by mutableStateOf(emptyList())
        internal set

    internal var path: String = ""

    /** Identifies [codeLenses] on the server, to run one; a newer list has another. */
    var codeLensToken: String = ""
        internal set
    internal var usagesJob: Job? = null

    /** Errors among [diagnostics]. */
    val errorCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.Error }

    /** Warnings among [diagnostics]. */
    val warningCount: Int get() = diagnostics.count { it.severity == DiagnosticSeverity.Warning }

    internal fun follow(change: TextChange) {
        diagnostics =
            diagnostics.mapNotNull { diagnostic ->
                shifted(
                    diagnostic.range,
                    change,
                )?.let { diagnostic.copy(range = it) }
            }
        usageCounts = usageCounts.mapNotNull { count -> shifted(count.range, change)?.let { count.copy(range = it) } }
        codeLenses = codeLenses.mapNotNull { lens -> shifted(lens.range, change)?.let { lens.copy(range = it) } }
        inlayHints = inlayHints.mapNotNull { shiftedHint(it, change) }
        semanticTokens = semanticTokens.mapNotNull { shiftedToken(it, change) }
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

/** The signature of the call around the caret (Ctrl+P, or typed `(` and `, `). */
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

internal fun inside(pos: TextPos, range: TextRange): Boolean {
    val start = TextPos(range.startLine, range.startColumn)
    val end = TextPos(range.endLine, range.endColumn)
    return pos >= start && pos <= end
}

internal fun textOf(document: Document, range: TextRange): String? {
    val sameLine = range.startLine == range.endLine
    val line = document.lines.getOrNull(range.startLine)?.takeIf { sameLine } ?: return null
    val start = range.startColumn.coerceIn(0, line.length)
    val end = range.endColumn.coerceIn(start, line.length)
    return line.substring(start, end).takeIf { it.isNotEmpty() }
}

// Ranges past the end of the text (the server computed them on a longer one) are cut to it.
internal fun clamp(diagnostics: List<Diagnostic>, document: Document): List<Diagnostic> {
    val lastLine = document.lines.lastIndex
    return diagnostics.filter { it.range.startLine <= lastLine }.map { diagnostic ->
        val range = diagnostic.range
        val endLine = range.endLine.coerceAtMost(lastLine)
        val startColumn = range.startColumn.coerceIn(0, document.lines[range.startLine].length)
        val endColumn = range.endColumn.coerceIn(0, document.lines[endLine].length)
        diagnostic.copy(range = TextRange(range.startLine, startColumn, endLine, endColumn))
    }
}

/** [path] with `/` separators and no trailing one, to compare the paths the server and the client name. */
fun normalized(path: String): String = path.replace('\\', '/').trimEnd('/')

internal fun sameFile(first: String, second: String) = normalized(first) == normalized(second)
