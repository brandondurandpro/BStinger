package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.example.kotlineditor.model.DocumentLinkInfo
import org.example.kotlineditor.model.DocumentLinksResult
import org.example.kotlineditor.model.FeatureStatus

private const val DOCUMENT_LINK_PROVIDER = "documentLinkProvider"
private const val TARGET = "target"

// `#L12`, `#12`, `#L12,5` or `#L12:5`: the line (from 1) a file link goes to.
private val LINE_FRAGMENT = Regex("""L?(\d+)(?:[,:]\d+)?""")

/** The links of a document (`textDocument/documentLink`, resolved with `documentLink/resolve` when needed). */
internal class DocumentLinkRequests(private val requests: ServerRequests, private val maxResolved: Int = MAX_RESOLVED) {
    /** The links of version [version] of [path], in document order. */
    suspend fun documentLinks(session: String, path: String, version: Int): DocumentLinksResult {
        val unsupported = DocumentLinksResult(FeatureStatus.Unsupported, emptyList())
        return requests.ask(session, path, version, DOCUMENT_LINK_PROVIDER, DocumentLinksResult.NotReady, unsupported) {
                document,
                request,
            ->
            val server = requests.serverOf(path, DOCUMENT_LINK_PROVIDER) ?: throw NotReadyException()
            val params = buildJsonObject { putJsonObject("textDocument") { put("uri", document.uri) } }
            val answer = request("textDocument/documentLink", params) as? JsonArray
            val raw = answer.orEmpty().mapNotNull { it as? JsonObject }
            val resolution = Resolution(server, DOCUMENT_LINK_PROVIDER, "documentLink/resolve", maxResolved)
            val links = resolution.resolved(raw, request) { it[TARGET] == null }.mapNotNull(::documentLinkOf)
            val sorted = links.sortedWith(compareBy({ it.range.startLine }, { it.range.startColumn }))
            DocumentLinksResult(FeatureStatus.Ready, sorted)
        }
    }

    private companion object {
        const val MAX_RESOLVED = 1_000
    }
}

/**
 * A link of a documentLink answer: a `file:` target opens the file, at the line of a `#L12` fragment; any other
 * (`https:`, `mailto:`) goes to the browser. Null without a target.
 */
fun documentLinkOf(link: JsonObject): DocumentLinkInfo? {
    val range = link["range"].textRange()
    val target = link.string(TARGET)
    if (range == null || target == null) return null
    val path = pathOfUri(target.substringBefore('#'))
    val fragment = target.substringAfter('#', "")
    val line =
        LINE_FRAGMENT
            .matchEntire(fragment)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
    val fileLine = line?.minus(1)?.coerceAtLeast(0)?.takeIf { path != null }
    return DocumentLinkInfo(range, path, fileLine, target.takeIf { path == null }, link.string("tooltip"))
}
