package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.DocumentLinkInfo
import org.example.kotlineditor.model.DocumentLinksResult
import org.example.kotlineditor.model.FeatureStatus

private const val PATH = "path"
private const val LINE = "line"
private const val URL = "url"
private const val TOOLTIP = "tooltip"

/** A document links answer. */
fun DocumentLinksResult.toJson(): JsonObject =
    buildJsonObject {
        put("status", status.name)
        put("links", JsonArray(links.map { it.toJson() }))
    }

private fun DocumentLinkInfo.toJson(): JsonObject =
    buildJsonObject {
        put("range", range.toJson())
        path?.let { put(PATH, it) }
        line?.let { put(LINE, it) }
        url?.let { put(URL, it) }
        tooltip?.let { put(TOOLTIP, it) }
    }

/** Reads a document links answer. */
fun JsonElement.documentLinksResult(): DocumentLinksResult {
    val fields = jsonObject
    val name = fields.string("status")
    val status = FeatureStatus.entries.firstOrNull { it.name == name } ?: FeatureStatus.NotReady
    val links = fields.getValue("links").jsonArray.map { it.jsonObject.documentLink() }
    return DocumentLinksResult(status, links)
}

private fun JsonObject.documentLink(): DocumentLinkInfo =
    DocumentLinkInfo(
        getValue("range").textRange(),
        stringOrNull(PATH),
        get(LINE)?.jsonPrimitive?.intOrNull,
        stringOrNull(URL),
        stringOrNull(TOOLTIP),
    )
