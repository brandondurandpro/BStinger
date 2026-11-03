package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.example.kotlineditor.model.HierarchyDirection
import org.example.kotlineditor.model.HierarchyItem
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.TextRange

/** The items of a prepareCallHierarchy, prepareTypeHierarchy, supertypes or subtypes answer. */
fun hierarchyItemsOf(answer: JsonElement?): List<HierarchyItem> =
    (answer as? JsonArray).objects().mapNotNull { hierarchyItemOf(it, null) }

/**
 * The items of an incomingCalls (`from`) or outgoingCalls (`to`) answer, with their number of calls. A caller goes
 * to its first call, a callee to its declaration.
 */
fun callsOf(answer: JsonElement?, direction: HierarchyDirection): List<HierarchyItem> {
    val callers = direction == HierarchyDirection.Callers
    val key = if (callers) "from" else "to"
    return (answer as? JsonArray).objects().mapNotNull { call ->
        val ranges = call.array("fromRanges").objects().mapNotNull { it.textRange() }
        val firstCall = ranges.firstOrNull()?.takeIf { callers }
        call.obj(key)?.let { hierarchyItemOf(it, firstCall) }?.copy(calls = ranges.size)
    }
}

// The item, at [at] (in its own file) or at its name; null outside of `file:` URIs.
private fun hierarchyItemOf(item: JsonObject, at: TextRange?): HierarchyItem? {
    val name = item.string("name")
    val path = item.string("uri")?.let(::pathOfUri)
    val range = at ?: item["selectionRange"].textRange() ?: item["range"].textRange()
    val location = path?.let { file -> range?.let { SourceLocation(file, it) } }
    return if (name != null && location != null) HierarchyItem(name, item.string("detail"), location, "$item") else null
}
