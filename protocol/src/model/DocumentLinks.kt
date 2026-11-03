package org.example.kotlineditor.model

/** A link of a document (a required file, a URL in a comment) that Ctrl+click follows. */
data class DocumentLinkInfo(
    /** The text of the link. */
    val range: TextRange,
    /** The file it opens, on the server machine; null for a web link. */
    val path: String? = null,
    /** The line of [path] it goes to (a `#L12` fragment), from 0; null for the top of the file. */
    val line: Int? = null,
    /** The web address it opens in the browser; null for a file. */
    val url: String? = null,
    /** What the server says of it. */
    val tooltip: String? = null,
)

/** The links of a document. */
data class DocumentLinksResult(
    /** Whether a server answered. */
    val status: FeatureStatus,
    /** The links, in document order. */
    val links: List<DocumentLinkInfo>,
) {
    /** Answers shared by every client. */
    companion object {
        /** No server could answer. */
        val NotReady = DocumentLinksResult(FeatureStatus.NotReady, emptyList())
    }
}
