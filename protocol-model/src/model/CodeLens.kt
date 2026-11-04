package org.example.kotlineditor.model

/** What a click on a code lens does. */
enum class CodeLensAction {
    /** Runs the lens's command on the language server, which may edit files. */
    Run,

    /** Lists the places the lens names (`2 implementations`). */
    ShowLocations,

    /** Nothing: the lens only informs. */
    None,
}

/** A code lens: a short text a language server attaches to a line (`2 implementations`, `Run test`). */
data class CodeLensInfo(
    /** The range it is about; it is shown at the end of its first line. */
    val range: TextRange,
    /** The text shown. */
    val title: String,
    /** What a click does. */
    val action: CodeLensAction,
    /** The places a [CodeLensAction.ShowLocations] lens lists. */
    val locations: List<SourceLocation> = emptyList(),
)

/** The code lenses of a document; [token] identifies them to run one. */
data class CodeLensesResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** Identifies this list on the server, for [LanguageServers.runCodeLens]. */
    val token: String,
    /** The lenses, in document order. */
    val lenses: List<CodeLensInfo>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = CodeLensesResult(FeatureStatus.NotReady, "", emptyList())
    }
}
