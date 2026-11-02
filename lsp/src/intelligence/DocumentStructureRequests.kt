package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.lsp.documents.OpenDocuments
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FoldingRangesResult
import org.example.kotlineditor.model.InlayHintsResult
import org.example.kotlineditor.model.OutlineResult
import org.example.kotlineditor.model.SelectionRangesResult

private const val URI = "uri"
private const val TEXT_DOCUMENT = "textDocument"
private const val DOCUMENT_SYMBOL_PROVIDER = "documentSymbolProvider"

/**
 * What the language server knows of the structure of a document: outline, inlay hints, folding and selection
 * ranges.
 */
internal class DocumentStructureRequests(private val documents: OpenDocuments, private val requests: ServerRequests) {
    /** The declarations of [path] at [version], nested, for File Structure. */
    suspend fun outline(session: String, path: String, version: Int): OutlineResult {
        val unsupported = OutlineResult(FeatureStatus.Unsupported, emptyList())
        return requests.ask(session, path, version, DOCUMENT_SYMBOL_PROVIDER, OutlineResult.NotReady, unsupported) {
                document,
                request,
            ->
            val params = buildJsonObject { putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) } }
            OutlineResult(FeatureStatus.Ready, outlineOf(request("textDocument/documentSymbol", params)))
        }
    }

    /** The inlay hints of version [version] of [path], over the whole document. */
    suspend fun inlayHints(session: String, path: String, version: Int): InlayHintsResult {
        val unsupported = InlayHintsResult(FeatureStatus.Unsupported, emptyList())
        return requests.ask(session, path, version, "inlayHintProvider", InlayHintsResult.NotReady, unsupported) {
                document,
                request,
            ->
            val lines = documents.locked { document.lines.size }
            val params =
                buildJsonObject {
                    putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) }
                    putJsonObject("range") {
                        put("start", position(0, 0))
                        put("end", position(lines, 0))
                    }
                }
            InlayHintsResult(FeatureStatus.Ready, inlayHintsOf(request("textDocument/inlayHint", params)))
        }
    }

    /** The regions of version [version] of [path] that can be folded. */
    suspend fun foldingRanges(session: String, path: String, version: Int): FoldingRangesResult {
        val unsupported = FoldingRangesResult(FeatureStatus.Unsupported, emptyMap())
        return requests.ask(session, path, version, "foldingRangeProvider", FoldingRangesResult.NotReady, unsupported) {
                document,
                request,
            ->
            val params = buildJsonObject { putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) } }
            FoldingRangesResult(FeatureStatus.Ready, foldingRegionsOf(request("textDocument/foldingRange", params)))
        }
    }

    /** The ranges Ctrl+W selects around [at], innermost first. */
    suspend fun selectionRanges(session: String, at: DocumentPosition): SelectionRangesResult {
        val unsupported = SelectionRangesResult(FeatureStatus.Unsupported, emptyList())
        return requests.ask(
            session,
            at.path,
            at.version,
            "selectionRangeProvider",
            SelectionRangesResult.NotReady,
            unsupported,
        ) {
                document,
                request,
            ->
            val params =
                buildJsonObject {
                    putJsonObject(TEXT_DOCUMENT) { put(URI, document.uri) }
                    put("positions", JsonArray(listOf(position(at.line, at.column))))
                }
            SelectionRangesResult(
                FeatureStatus.Ready,
                selectionRangesOf(request("textDocument/selectionRange", params)),
            )
        }
    }

    private fun position(line: Int, column: Int) =
        buildJsonObject {
            put("line", line)
            put("character", column)
        }
}
