package org.example.kotlineditor.model

/** A word the language server classifies (textDocument/semanticTokens): a class, a parameter, a static method... */
data class SemanticToken(
    /** Its line. */
    val line: Int,
    /** Column of its first character. */
    val column: Int,
    /** Its number of characters, on its line. */
    val length: Int,
    /** Its LSP token type: `class`, `method`, `parameter`, `property`... */
    val type: String,
    /** Its LSP modifiers: `declaration`, `static`, `deprecated`, `readonly`... */
    val modifiers: Set<String> = emptySet(),
)

/** The semantic tokens of a document, in the order of the text. */
data class SemanticTokensResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** Sorted by line, then column. */
    val tokens: List<SemanticToken>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = SemanticTokensResult(FeatureStatus.NotReady, emptyList())
    }
}
