package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.HierarchyItem
import org.example.kotlineditor.model.HierarchyResult

private const val DETAIL = "detail"

/** A hierarchy item. */
fun HierarchyItem.toJson(): JsonObject =
    buildJsonObject {
        put("name", name)
        put(DETAIL, detail?.let(::JsonPrimitive) ?: JsonNull)
        put("location", location.toJson())
        put("data", data)
        put("calls", calls)
    }

/** Reads a hierarchy item. */
fun JsonElement.hierarchyItem(): HierarchyItem {
    val fields = jsonObject
    return HierarchyItem(
        name = fields.getValue("name").jsonPrimitive.content,
        detail = (fields[DETAIL] as? JsonPrimitive)?.contentOrNull,
        location = fields.getValue("location").jsonObject.sourceLocation(),
        data = fields.getValue("data").jsonPrimitive.content,
        calls = fields.getValue("calls").jsonPrimitive.int,
    )
}

/** A hierarchy answer. */
fun HierarchyResult.toJson(): JsonObject =
    buildJsonObject {
        put("status", status.name)
        put("items", buildJsonArray { items.forEach { add(it.toJson()) } })
    }

/** Reads a hierarchy answer. */
fun JsonElement.hierarchyResult(): HierarchyResult {
    val fields = jsonObject
    val name = fields.getValue("status").jsonPrimitive.content
    val status = FeatureStatus.entries.firstOrNull { it.name == name } ?: FeatureStatus.NotReady
    val items = fields.getValue("items").jsonArray.map { it.hierarchyItem() }
    return HierarchyResult(status, items)
}
