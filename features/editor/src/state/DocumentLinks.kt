package org.example.kotlineditor.state

import org.example.kotlineditor.model.DocumentLinkInfo
import org.example.kotlineditor.model.FeatureStatus

// The links of the language server (a required file, a URL in a comment): Ctrl+click follows them.

/** The links of the text the usages were counted on. */
internal suspend fun CodeInsightState.documentLinksFromServer(insight: DocumentInsight) {
    val document = insight.document
    val version = document.version
    val result = askVersion(insight.path, document) { servers, at -> servers.documentLinks(insight.path, at) }
    val ready = result?.takeIf { it.status == FeatureStatus.Ready && document.version == version }
    ready?.let { insight.links = it.links }
}

/** The link of [document] at [pos]; null when there is none. */
fun CodeInsightState.linkAt(document: Document, pos: TextPos): DocumentLinkInfo? =
    insightOf(document)?.links?.firstOrNull { link ->
        val range = link.range
        val afterStart = TextPos(range.startLine, range.startColumn) <= pos
        afterStart && pos < TextPos(range.endLine, range.endColumn)
    }

/** Ctrl+click on a link: opens its file (at its line), or its address in the browser; false when there is none. */
fun CodeInsightState.followLink(document: Document, pos: TextPos): Boolean {
    val target = linkAt(document, pos) ?: return false
    dismissHover()
    link = null
    val path = target.path
    val url = target.url
    when {
        path != null -> host.navigate(path, TextPos(target.line ?: 0, 0))
        url != null -> host.openUrl(url)
    }
    return true
}
