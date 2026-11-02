package org.example.kotlineditor.model

// What the language servers know about the code (see #97), as the IDE asks for it: positions are lines and UTF-16
// columns of the text the client sent, lines separated by `\n`, like TextChange.

/** Whether a language server could answer a request. */
enum class FeatureStatus {
    /** It answered: the result holds what it knows, possibly nothing. */
    Ready,

    /** No server could answer now: none for this file, starting, crashed, restarted during the request. */
    NotReady,

    /** The server of this file does not offer this feature (phpactor does not format, for instance). */
    Unsupported,
}

/** A range of a document, from a position to another (excluded). */
data class TextRange(
    /** Line where it starts. */
    val startLine: Int,
    /** Column where it starts. */
    val startColumn: Int,
    /** Line where it ends. */
    val endLine: Int,
    /** Column where it ends (excluded). */
    val endColumn: Int,
)

/** A position of an open document, at the version the request is about. */
data class DocumentPosition(
    /** Path of the file, as the client names it. */
    val path: String,
    /** Version of the document the position is in: the server waits for it before asking. */
    val version: Int,
    /** Line. */
    val line: Int,
    /** UTF-16 column. */
    val column: Int,
)

/** A place in a file of the server machine. */
data class SourceLocation(
    /** Path of the file. */
    val path: String,
    /** Range of the symbol, or of the usage. */
    val range: TextRange,
    /** The text of the line where [range] starts, to show in a list of usages; null when unknown. */
    val preview: String? = null,
)

/** Documentation of the symbol under the mouse or the caret. */
data class HoverResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** The documentation, in Markdown; null when there is none. */
    val markdown: String?,
    /** What it documents, when the server says. */
    val range: TextRange? = null,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = HoverResult(FeatureStatus.NotReady, null)
    }
}

/** Where a navigation goes: what Ctrl+B, Ctrl+Shift+B and Ctrl+Alt+B ask for. */
enum class NavigationKind {
    /** The declaration or definition of the symbol (Ctrl+B, Ctrl+click). */
    Definition,

    /** The declaration of the type of the symbol (Ctrl+Shift+B). */
    TypeDefinition,

    /** The implementations of an interface or abstract member (Ctrl+Alt+B). */
    Implementation,
}

/** Places a navigation or a search for usages found. */
data class LocationsResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** The places, in the server's order; empty when none. */
    val locations: List<SourceLocation>,
    /** More places exist than were sent. */
    val truncated: Boolean = false,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = LocationsResult(FeatureStatus.NotReady, emptyList())

        /** The server does not offer it. */
        val Unsupported = LocationsResult(FeatureStatus.Unsupported, emptyList())
    }
}

/** How a highlighted occurrence of a symbol uses it. */
enum class HighlightKind {
    /** A textual occurrence. */
    Text,

    /** The symbol is read. */
    Read,

    /** The symbol is written (assigned, declared). */
    Write,
}

/** An occurrence of the symbol at the caret in the same document. */
data class SymbolHighlight(
    /** Where it is. */
    val range: TextRange,
    /** How it uses the symbol. */
    val kind: HighlightKind,
)

/** One parameter of a signature, by its place in the signature's label. */
data class SignatureParameter(
    /** Offset of the parameter's text in [SignatureInfo.label]. */
    val start: Int,
    /** End of the parameter's text in [SignatureInfo.label] (excluded). */
    val end: Int,
    /** Its documentation, in Markdown; null when none. */
    val documentation: String?,
)

/** One signature of the function being called. */
data class SignatureInfo(
    /** The whole signature, as the server writes it (`greet(string $name): string`). */
    val label: String,
    /** Its documentation, in Markdown; null when none. */
    val documentation: String?,
    /** Its parameters, in order. */
    val parameters: List<SignatureParameter>,
)

/** The signatures of the call around the caret, and the parameter being typed (Ctrl+P). */
data class SignatureHelpResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** The signatures, empty when the caret is not in a call. */
    val signatures: List<SignatureInfo>,
    /** Index of the signature that matches best. */
    val activeSignature: Int,
    /** Index of the parameter at the caret, -1 when none. */
    val activeParameter: Int,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = SignatureHelpResult(FeatureStatus.NotReady, emptyList(), 0, -1)
    }
}

/** How many times a declaration of a document is used, for the "3 usages" hint at the end of its line. */
data class UsageCount(
    /** Where the declared name is. */
    val range: TextRange,
    /** Name declared. */
    val name: String,
    /** Usages found, the declaration excluded. */
    val count: Int,
)

/** The usage counts of a document. */
data class UsageCountsResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** One per declaration (classes, functions, methods, properties, constants), in document order. */
    val counts: List<UsageCount>,
)

/** How serious a diagnostic is. */
enum class DiagnosticSeverity {
    /** An error: red wavy underline. */
    Error,

    /** A warning: yellow wavy underline. */
    Warning,

    /** Information: grey underline. */
    Information,

    /** A hint, barely visible. */
    Hint,
}

/** A problem a language server found in a document. */
data class Diagnostic(
    /** Where it is. */
    val range: TextRange,
    /** How serious it is. */
    val severity: DiagnosticSeverity,
    /** What is wrong, as the server says it. */
    val message: String,
    /** Who found it (`phpactor`), when the server says. */
    val source: String? = null,
    /** Its code (`worse.unresolved_name`), when the server gives one. */
    val code: String? = null,
    /** Unused code: shown greyed out. */
    val unnecessary: Boolean = false,
    /** Deprecated code: shown struck through. */
    val deprecated: Boolean = false,
)

/** The diagnostics of one open document. */
data class FileDiagnostics(
    /** Path of the document. */
    val path: String,
    /** Its diagnostics, in document order. */
    val diagnostics: List<Diagnostic>,
)

/** The diagnostics of every document a session has open, as one snapshot: a newer one replaces an older one. */
data class DiagnosticsSnapshot(
    /** Increases with every change; the client keeps the snapshot with the highest. */
    val version: Long,
    /** One entry per open document that has a language server, diagnostics or not. */
    val files: List<FileDiagnostics>,
) {
    /** Snapshots shared by every client. */
    companion object {
        /** Before the first answer. */
        val Empty = DiagnosticsSnapshot(0, emptyList())
    }
}

/** What an edit of several files does to one of them. */
data class FileChange(
    /** Path of the file. */
    val path: String,
    /** Edits of its text, in the coordinates of the text before any of them, in no particular order. */
    val edits: List<TextChange>,
    /** The file is created first (empty) when it does not exist. */
    val create: Boolean = false,
)

/** The edits a rename, a formatting or a quick fix asks for, which the client applies to its documents. */
data class EditResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** The files changed, each once. */
    val changes: List<FileChange>,
    /** Why nothing can be done (`Cannot rename this element`), shown to the user; null when it went fine. */
    val message: String? = null,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = EditResult(FeatureStatus.NotReady, emptyList())

        /** The server does not offer it. */
        val Unsupported = EditResult(FeatureStatus.Unsupported, emptyList())
    }
}

/** What can be renamed at a position, before asking for the new name. */
data class RenameTarget(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** The range of the name to rename; null when nothing can be renamed there. */
    val range: TextRange?,
    /** The current name, proposed in the rename dialog. */
    val placeholder: String?,
    /** Why it cannot be renamed, when the server says. */
    val message: String? = null,
)

/** One quick fix or refactoring offered at the caret (Alt+Enter). */
data class CodeActionInfo(
    /** Title shown in the list. */
    val title: String,
    /** Kind (`quickfix.import_class`, `refactor.extract.method`), when the server gives one. */
    val kind: String?,
    /** The server recommends it for the problem at the caret. */
    val preferred: Boolean = false,
)

/** The quick fixes offered at a range; [token] identifies them to apply one. */
data class CodeActionsResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** Identifies this list on the server, for [LanguageServers.applyCodeAction]. */
    val token: String,
    /** The actions, best first. */
    val actions: List<CodeActionInfo>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = CodeActionsResult(FeatureStatus.NotReady, "", emptyList())
    }
}

/** Options of a formatting. */
data class FormatOptions(
    /** Width of a tab in spaces. */
    val tabSize: Int = 4,
    /** Indent with spaces rather than tabs. */
    val insertSpaces: Boolean = true,
)

/**
 * What the language servers answer about the code of the open documents (see #97). Every request names the version
 * of the document it is about: the server waits for that version before asking its language server. The defaults
 * answer that no server is ready, for an IDE without language servers.
 */
@Suppress("TooManyFunctions", "ComplexInterface")
interface CodeIntelligence {
    /** Documentation of the symbol at [at] (hover, Ctrl+Q). */
    suspend fun hover(at: DocumentPosition): HoverResult = HoverResult.NotReady

    /** Where the symbol at [at] is declared, its type, or its implementations. */
    suspend fun navigate(at: DocumentPosition, kind: NavigationKind): LocationsResult = LocationsResult.NotReady

    /** The usages of the symbol at [at], with its declaration when [includeDeclaration]. */
    suspend fun references(at: DocumentPosition, includeDeclaration: Boolean): LocationsResult =
        LocationsResult.NotReady

    /** The occurrences of the symbol at [at] in its document. */
    suspend fun highlights(at: DocumentPosition): List<SymbolHighlight> = emptyList()

    /** The signatures of the call around [at]. */
    suspend fun signatureHelp(at: DocumentPosition): SignatureHelpResult = SignatureHelpResult.NotReady

    /** How many times each declaration of [path] at [version] is used. */
    suspend fun usageCounts(path: String, version: Int): UsageCountsResult =
        UsageCountsResult(FeatureStatus.NotReady, emptyList())

    /**
     * The diagnostics of the documents this session has open, once newer than [since], or the current ones after a
     * while (a long poll, like the status of the servers).
     */
    suspend fun diagnostics(since: Long): DiagnosticsSnapshot = kotlinx.coroutines.awaitCancellation()

    /** What can be renamed at [at] (Shift+F6). */
    suspend fun prepareRename(at: DocumentPosition): RenameTarget = RenameTarget(FeatureStatus.NotReady, null, null)

    /** The edits that rename the symbol at [at] to [newName], in every file. */
    suspend fun rename(at: DocumentPosition, newName: String): EditResult = EditResult.NotReady

    /** The edits that format [path] at [version], or only [range] of it (Ctrl+Alt+L). */
    suspend fun format(path: String, version: Int, range: TextRange?, options: FormatOptions): EditResult =
        EditResult.NotReady

    /** The quick fixes and refactorings offered for [range] of [path] at [version] (Alt+Enter). */
    suspend fun codeActions(path: String, version: Int, range: TextRange): CodeActionsResult =
        CodeActionsResult.NotReady

    /** The edits of action [index] of the list [token] (running its command on the server when it has one). */
    suspend fun applyCodeAction(token: String, index: Int): EditResult = EditResult.NotReady

    /** The code lenses of [path] at [version] (`2 implementations`, `Run test`). */
    suspend fun codeLenses(path: String, version: Int): CodeLensesResult = CodeLensesResult.NotReady

    /** Runs the command of lens [index] of the list [token] on the server: the edits it asked for. */
    suspend fun runCodeLens(token: String, index: Int): EditResult = EditResult.NotReady

    /** The declarations of [path] at [version] (File Structure). */
    suspend fun outline(path: String, version: Int): OutlineResult = OutlineResult.NotReady

    /** The function or type at [at], root of the call or type hierarchy [kind]; none when it is neither. */
    suspend fun prepareHierarchy(at: DocumentPosition, kind: HierarchyKind): HierarchyResult = HierarchyResult.NotReady

    /** The items on side [direction] of [item], found from the document [path] (the one the hierarchy started in). */
    suspend fun hierarchyChildren(path: String, item: HierarchyItem, direction: HierarchyDirection): HierarchyResult =
        HierarchyResult.NotReady

    /** The semantic tokens of version [version] of [path], to color the code as the server understands it. */
    suspend fun semanticTokens(path: String, version: Int): SemanticTokensResult = SemanticTokensResult.NotReady

    /** The inlay hints of version [version] of [path]: parameter names, types. */
    suspend fun inlayHints(path: String, version: Int): InlayHintsResult = InlayHintsResult.NotReady

    /** The regions of version [version] of [path] that can be folded. */
    suspend fun foldingRanges(path: String, version: Int): FoldingRangesResult = FoldingRangesResult.NotReady

    /** The ranges Ctrl+W selects around [at], innermost first. */
    suspend fun selectionRanges(at: DocumentPosition): SelectionRangesResult = SelectionRangesResult.NotReady

    /** The declarations of the session's project named like [query] (Go to Symbol). */
    suspend fun workspaceSymbols(query: String): WorkspaceSymbolsResult = WorkspaceSymbolsResult.NotReady
}
