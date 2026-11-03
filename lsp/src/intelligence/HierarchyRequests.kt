package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.model.HierarchyItem
import org.example.kotlineditor.model.HierarchyKind
import org.example.kotlineditor.model.HierarchyResult
import org.example.kotlineditor.model.SourceLocation

private val UNSUPPORTED = HierarchyResult(FeatureStatus.Unsupported, emptyList())

/** The call hierarchy (Ctrl+Alt+H) and the type hierarchy (Ctrl+H), from the language server of a document. */
internal class HierarchyRequests(
    private val requests: ServerRequests,
    private val previews: (List<SourceLocation>) -> List<SourceLocation>,
) {
    /** The function or type at [at]. */
    suspend fun prepare(session: String, at: DocumentPosition, kind: HierarchyKind): HierarchyResult {
        val method =
            if (kind ==
                HierarchyKind.Calls
            ) {
                "textDocument/prepareCallHierarchy"
            } else {
                "textDocument/prepareTypeHierarchy"
            }
        return requests.ask(session, at.path, at.version, capabilityOf(kind), HierarchyResult.NotReady, UNSUPPORTED) {
                document,
                request,
            ->
            val params =
                buildJsonObject {
                    putJsonObject("textDocument") { put("uri", document.uri) }
                    put("position", lspPosition(at.line, at.column))
                }
            ready(hierarchyItemsOf(request(method, params)))
        }
    }

    /** The items on side [direction] of [item], asked to the server of the open document [path]. */
    suspend fun children(path: String, item: HierarchyItem, direction: HierarchyDirection): HierarchyResult {
        val server = requests.serverOf(path, capabilityOf(direction.kind)) ?: return HierarchyResult.NotReady
        val lspItem = runCatching { Json.parseToJsonElement(item.data) }.getOrNull() as? JsonObject
        val params = buildJsonObject { lspItem?.let { put("item", it) } }
        return requests.answeredBy(server, HierarchyResult.NotReady) { request ->
            val answer = request(methodOf(direction), params)
            val items =
                when (direction) {
                    HierarchyDirection.Callers, HierarchyDirection.Callees -> callsOf(answer, direction)
                    else -> hierarchyItemsOf(answer)
                }
            ready(items)
        }
    }

    // The items with the text of the line they are on.
    private fun ready(items: List<HierarchyItem>): HierarchyResult {
        val located = previews(items.map { it.location })
        val withText = items.zip(located) { item, location -> item.copy(location = location) }
        return HierarchyResult(FeatureStatus.Ready, withText)
    }

    private fun capabilityOf(kind: HierarchyKind) =
        if (kind == HierarchyKind.Calls) "callHierarchyProvider" else "typeHierarchyProvider"

    private fun methodOf(direction: HierarchyDirection) =
        when (direction) {
            HierarchyDirection.Callers -> "callHierarchy/incomingCalls"
            HierarchyDirection.Callees -> "callHierarchy/outgoingCalls"
            HierarchyDirection.Supertypes -> "typeHierarchy/supertypes"
            HierarchyDirection.Subtypes -> "typeHierarchy/subtypes"
        }

    private fun lspPosition(line: Int, column: Int) =
        buildJsonObject {
            put("line", line)
            put("character", column)
        }
}
