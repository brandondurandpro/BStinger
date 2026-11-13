package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.TextChange

private const val USAGES_DELAY_MS = 800L
private const val NOT_READY_RETRY_MS = 1_000L
private const val NOT_READY_RETRIES = 30

/**
 * What the language servers say about each open document, asked once typing pauses: the usage counts, the inlay
 * hints, the folding regions and the semantic tokens, plus what [askMore] asks (code lenses, links…), and the
 * diagnostics the servers send. [onEdit] is told of every edit of a followed document.
 */
@Stable
@Suppress("TooManyFunctions")
internal class DocumentInsights(
    private val scope: CoroutineScope,
    private val requests: InsightRequests,
    private val onEdit: (DocumentInsight, TextChange) -> Unit,
    private val askMore: suspend (DocumentInsight, decorated: Boolean) -> Unit,
) {
    private val insights = HashMap<Document, DocumentInsight>()
    private var lastDiagnostics: DiagnosticsSnapshot = DiagnosticsSnapshot.Empty

    /** How the inlay hints are shown; changing it shows those already known the new way. */
    var inlayMode: InlayHintsMode
        get() = mode
        set(value) {
            mode = value
            insights.values.forEach(::applyInlays)
        }

    private var mode by mutableStateOf(InlayHintsMode.Popup)

    /**
     * Whether the decorations of the servers (inlay hints, semantic colors, code lenses, links) are asked and shown;
     * off, those already known go, and on again everything is asked again.
     */
    var decorated: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            if (value) refreshAll() else insights.values.forEach(::dropDecorations)
        }

    private fun dropDecorations(insight: DocumentInsight) {
        insight.inlayHints = emptyList()
        insight.semanticTokens = emptyList()
        insight.codeLenses = emptyList()
        insight.links = emptyList()
        insight.document.inlays = emptyMap()
    }

    /** What the language server said about [document], null when it has none. */
    fun insightOf(document: Document): DocumentInsight? = insights[document]

    /** The documents open with a language server, by path: new ones are followed, closed ones forgotten. */
    fun track(documents: Map<String, Document>) {
        val wanted = documents.entries.associate { (path, document) -> document to path }
        val gone = insights.keys.filter { it !in wanted }
        for (document in gone) forget(document)
        for ((document, path) in wanted) {
            val existing = insights[document]
            if (existing == null) follow(document, path) else existing.path = path
        }
        applyDiagnostics(lastDiagnostics)
    }

    private fun follow(document: Document, path: String) {
        val insight = DocumentInsight(document).also { it.path = path }
        insights[document] = insight
        document.markerListener = { change -> onEdit(insight, change) }
        refreshUsages(insight, delayed = false)
    }

    private fun forget(document: Document) {
        document.inlays = emptyMap()
        insights.remove(document)?.usagesJob?.cancel()
        document.markerListener = null
    }

    /** The server sent newer diagnostics. */
    fun applyDiagnostics(snapshot: DiagnosticsSnapshot) {
        lastDiagnostics = snapshot
        val byPath = snapshot.files.associate { normalized(it.path) to it.diagnostics }
        for (insight in insights.values) {
            val diagnostics = byPath[normalized(insight.path)]
            if (diagnostics != null) insight.diagnostics = clamp(diagnostics, insight.document)
        }
    }

    /** Asks everything again; a document still asking finishes first, then asks again. */
    fun refreshAll() =
        insights.values.forEach { insight ->
            insight.typeFormattingTriggers = null
            val asking = insight.usagesJob?.isActive == true
            if (asking) insight.askAgain = true else refreshUsages(insight, delayed = false)
        }

    /** Asks everything about [insight], once typing pauses when [delayed], and again while the server is not ready. */
    fun refreshUsages(insight: DocumentInsight, delayed: Boolean) {
        insight.usagesJob?.cancel()
        insight.usagesJob =
            scope.launch {
                if (delayed) delay(USAGES_DELAY_MS)
                do {
                    insight.askAgain = false
                    askEverything(insight)
                } while (insight.askAgain)
            }
    }

    private suspend fun askEverything(insight: DocumentInsight) {
        countUsages(insight)
        askMore(insight, decorated)
        foldingFromServer(insight)
        if (!decorated) return
        inlayHintsFromServer(insight)
        semanticTokensFromServer(insight)
    }

    private suspend fun countUsages(insight: DocumentInsight) {
        var attempts = 0
        var settled = false
        while (!settled && attempts < NOT_READY_RETRIES) {
            attempts++
            val version = insight.document.version
            val result =
                requests.askVersion(insight.path, insight.document) { servers, at ->
                    servers.usageCounts(insight.path, at)
                }
            val current = insight.document.version == version
            val status = result?.status.takeIf { current }
            if (status == FeatureStatus.Ready) insight.usageCounts = result?.counts.orEmpty()
            settled = status == FeatureStatus.Ready || status == FeatureStatus.Unsupported
            if (!settled) delay(NOT_READY_RETRY_MS)
        }
    }

    // The inlay hints of the text the usages were counted on.
    private suspend fun inlayHintsFromServer(insight: DocumentInsight) {
        val document = insight.document
        val version = document.version
        val result = requests.askVersion(insight.path, document) { servers, at -> servers.inlayHints(insight.path, at) }
        val ready = result?.takeIf { it.status == FeatureStatus.Ready && document.version == version }
        ready?.let {
            insight.inlayHints = shownHints(it.hints, document.lines)
            applyInlays(insight)
        }
    }

    // The semantic tokens of the text the usages were counted on.
    private suspend fun semanticTokensFromServer(insight: DocumentInsight) {
        val document = insight.document
        val version = document.version
        val result =
            requests.askVersion(insight.path, document) { servers, at -> servers.semanticTokens(insight.path, at) }
        val ready = result?.takeIf { it.status == FeatureStatus.Ready && document.version == version }
        ready?.let { insight.semanticTokens = it.tokens }
    }

    /** Inline, the hints are drawn in the code; otherwise the code has none. */
    fun applyInlays(insight: DocumentInsight) {
        val inline = mode == InlayHintsMode.Inline
        insight.document.inlays = if (inline) inlaysOf(insight.inlayHints) else emptyMap()
    }

    // The regions only the server knows how to fold, once it counted the usages of the same text.
    private suspend fun foldingFromServer(insight: DocumentInsight) {
        val document = insight.document
        val version = document.version
        val result =
            requests.askVersion(insight.path, document) { servers, at -> servers.foldingRanges(insight.path, at) }
        val ready = result?.takeIf { it.status == FeatureStatus.Ready && document.version == version }
        ready?.let { document.folding.serverRegions(it.regions, document.lineCount) }
    }
}
