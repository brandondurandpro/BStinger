package org.example.kotlineditor.lsp.intelligence

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.documents.MirroredDocument
import org.example.kotlineditor.lsp.supervisor.ManagedServer
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.model.UsageCount
import org.example.kotlineditor.model.UsageCountsResult
import java.util.concurrent.ConcurrentHashMap

/** The usage counts of a document and the usages behind them, found by its language server. */
internal class UsageCounter(
    private val settings: IntelligenceSettings,
    private val serversOf: (MirroredDocument) -> List<ManagedServer>,
) {
    /** The counts, and the usages of each counted declaration. */
    class Counted(val result: UsageCountsResult, val usages: Map<TextRange, List<SourceLocation>>)

    /**
     * Counts the usages of the declarations of [document], a few at a time, for [IntelligenceSettings.countBudgetMs]:
     * a document with many declarations keeps the counts found by then rather than none.
     */
    suspend fun count(document: MirroredDocument, request: suspend (String, JsonObject) -> JsonElement?): Counted {
        val symbolParams = buildJsonObject { putJsonObject("textDocument") { put("uri", document.uri) } }
        val symbols = countedSymbolsOf(request("textDocument/documentSymbol", symbolParams))
        val counted = symbols.take(settings.maxCountedSymbols)
        val supportsReferences = serversOf(document).any { it.supports("referencesProvider") == true }
        if (!supportsReferences) {
            return Counted(UsageCountsResult(FeatureStatus.Unsupported, emptyList()), emptyMap())
        }
        val permits = Semaphore(settings.countParallelism)
        val found = ConcurrentHashMap<TextRange, Pair<UsageCount, List<SourceLocation>>>()
        withTimeoutOrNull(settings.countBudgetMs) {
            coroutineScope {
                counted.forEach { symbol ->
                    launch { permits.withPermit { found[symbol.range] = usagesOf(document, symbol, request) } }
                }
            }
        }
        val counts = counted.mapNotNull { found[it.range]?.first }
        return Counted(UsageCountsResult(FeatureStatus.Ready, counts), found.mapValues { it.value.second })
    }

    private suspend fun usagesOf(
        document: MirroredDocument,
        symbol: CountedSymbol,
        request: suspend (String, JsonObject) -> JsonElement?,
    ): Pair<UsageCount, List<SourceLocation>> {
        val start = symbol.range
        val params =
            buildJsonObject {
                putJsonObject("textDocument") { put("uri", document.uri) }
                putJsonObject("position") {
                    put("line", start.startLine)
                    put("character", start.startColumn)
                }
                putJsonObject("context") { put("includeDeclaration", false) }
            }
        val usages = locationsOf(request("textDocument/references", params))
        // Some servers count the declaration even when asked not to.
        val others = usages.filterNot { it.path == document.path && contains(it.range, start) }
        return UsageCount(symbol.range, symbol.name, others.size) to others
    }
}
