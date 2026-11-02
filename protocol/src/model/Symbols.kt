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
