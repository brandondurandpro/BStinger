package org.example.kotlineditor.model

import org.example.kotlineditor.syntax.Declaration

/** The declarations of a document, from its language server, as File Structure (Ctrl+F12) lists them. */
data class OutlineResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** In document order, nested ones right after their parent with a greater depth. */
    val declarations: List<Declaration>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = OutlineResult(FeatureStatus.NotReady, emptyList())
    }
}

/** A declaration of the project found by name (Go to Symbol). */
data class WorkspaceSymbol(
    /** Absolute path of its file. */
    val path: String,
    /** Its name, kind and position; the depth is 0. */
    val declaration: Declaration,
    /** The class or namespace it belongs to, if the server says. */
    val container: String?,
)

/** The declarations of the project whose name matches a query. */
data class WorkspaceSymbolsResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** As the servers sorted them. */
    val symbols: List<WorkspaceSymbol>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = WorkspaceSymbolsResult(FeatureStatus.NotReady, emptyList())
    }
}

/** The ranges Ctrl+W selects one after the other around a position, innermost first. */
data class SelectionRangesResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** Each one contains the previous one. */
    val ranges: List<TextRange>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = SelectionRangesResult(FeatureStatus.NotReady, emptyList())
    }
}

/** The regions of a document the language server says can be folded: start line -> end line. */
data class FoldingRangesResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** Each one starts on its key line and ends on its value line, after it. */
    val regions: Map<Int, Int>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = FoldingRangesResult(FeatureStatus.NotReady, emptyMap())
    }
}

/** What an inlay hint names: a parameter of the call, the type of what is before it, or something else. */
enum class InlayHintKind {
    /** `name:` before an argument. */
    Parameter,

    /** `: string` after a variable. */
    Type,

    /** Any other hint. */
    Other,
}

/** A hint the language server shows in the code without it being text of the file: `name:` before an argument. */
data class InlayHint(
    /** Line it is on. */
    val line: Int,
    /** Column of the character it is drawn before. */
    val column: Int,
    /** What it shows, its spaces included (`name: `). */
    val label: String,
    /** What it names. */
    val kind: InlayHintKind,
)

/** The inlay hints of a document, in the order of the text. */
data class InlayHintsResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** Sorted by line, then column. */
    val hints: List<InlayHint>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = InlayHintsResult(FeatureStatus.NotReady, emptyList())
    }
}
