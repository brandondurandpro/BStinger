package org.example.kotlineditor.lsp.intelligence

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import org.example.kotlineditor.model.InlayHint
import org.example.kotlineditor.model.InlayHintKind
import org.example.kotlineditor.model.TextRange

private const val RANGE = "range"

/**
 * The ranges of the first SelectionRange of a selectionRange answer, innermost first (its parents in order); a range
 * equal to the previous one or not containing it is left out.
 */
fun selectionRangesOf(answer: JsonElement?): List<TextRange> {
    val found = ArrayList<TextRange>()
    var current = (answer as? JsonArray)?.firstOrNull() as? JsonObject
    while (current != null) {
        val range = current[RANGE].textRange()
        val previous = found.lastOrNull()
        range?.takeIf { grows(it, previous) }?.let { found += it }
        current = current.obj("parent")
    }
    return found
}

// A parent range is one more step when it holds the previous one and is larger.
private fun grows(range: TextRange, previous: TextRange?): Boolean {
    if (previous == null) return true
    return range != previous && contains(range, previous)
}

/**
 * The regions of a foldingRange answer, start line -> end line; a range on one line is left out, and of two
 * starting on the same line the longer one is kept.
 */
fun foldingRegionsOf(answer: JsonElement?): Map<Int, Int> {
    val regions = HashMap<Int, Int>()
    for (range in (answer as? JsonArray).objects()) {
        val start = range.int("startLine") ?: -1
        val end = range.int("endLine") ?: -1
        if (start >= 0 && end > start) regions[start] = maxOf(regions[start] ?: 0, end)
    }
    return regions
}

/**
 * The hints of an inlayHint answer, sorted: a label made of parts is joined, and the padding the server asks for
 * becomes spaces of the label (`name:` with paddingRight is `name: `). Kind 1 is a type, 2 a parameter.
 */
fun inlayHintsOf(answer: JsonElement?): List<InlayHint> {
    val hints =
        (answer as? JsonArray).objects().mapNotNull { hint ->
            val position = hint.obj("position")
            val line = position?.int("line")
            val column = position?.int("character")
            val label = labelOf(hint["label"])
            hint.takeIf { line != null && column != null && label.isNotBlank() }?.let {
                InlayHint(line ?: 0, column ?: 0, padded(hint, label), hintKindOf(hint.int("kind")))
            }
        }
    return hints.sortedWith(compareBy({ it.line }, { it.column }))
}

private fun labelOf(label: JsonElement?): String =
    when (label) {
        is JsonPrimitive -> label.contentOrNull.orEmpty()
        is JsonArray -> label.objects().joinToString("") { it.string("value").orEmpty() }
        else -> ""
    }

private fun padded(hint: JsonObject, label: String): String {
    val left = if (hint.boolean("paddingLeft")) " " else ""
    val right = if (hint.boolean("paddingRight")) " " else ""
    return left + label + right
}

private fun JsonObject.boolean(key: String) = (get(key) as? JsonPrimitive)?.booleanOrNull == true

private fun hintKindOf(kind: Int?): InlayHintKind =
    when (kind) {
        1 -> InlayHintKind.Type
        2 -> InlayHintKind.Parameter
        else -> InlayHintKind.Other
    }
