package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CodeLensAction
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus

// The code lenses of the language server (`2 implementations`, `Run test`), shown after the usage hint of their line.

/** The lenses of the text the usages were counted on. */
internal suspend fun CodeInsightState.codeLensesFromServer(insight: DocumentInsight) {
    val document = insight.document
    val version = document.version
    val result = askVersion(insight.path, document) { servers, at -> servers.codeLenses(insight.path, at) }
    val ready = result?.takeIf { it.status == FeatureStatus.Ready && document.version == version }
    ready?.let {
        insight.codeLensToken = it.token
        insight.codeLenses = it.lenses
    }
}

/** A click on lens [index] of [document]: lists its places, or runs its command on the server and applies its edits. */
fun CodeInsightState.clickCodeLens(document: Document, index: Int) {
    val insight = insightOf(document) ?: return
    val lens = insight.codeLenses.getOrNull(index) ?: return
    val anchor = TextPos(lens.range.startLine, lens.range.startColumn)
    dismissHover()
    when (lens.action) {
        CodeLensAction.ShowLocations -> showLocations(lens.title, lens.locations, document, anchor)
        CodeLensAction.Run -> runCodeLens(insight, index, anchor)
        CodeLensAction.None -> Unit
    }
}

private fun CodeInsightState.runCodeLens(insight: DocumentInsight, index: Int, anchor: TextPos) {
    val token = insight.codeLensToken
    scope.launch {
        val result = askServers { servers -> servers.runCodeLens(token, index) } ?: EditResult.NotReady
        applyResult(insight.document, anchor, result, "run this lens")
    }
}
