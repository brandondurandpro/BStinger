package org.example.kotlineditor.model

/** The characters whose typing makes the language server format around them (`;`, `}`). */
data class TypeFormattingTriggers(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** The characters, `\n` for Enter. */
    val characters: List<String>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = TypeFormattingTriggers(FeatureStatus.NotReady, emptyList())
    }
}
