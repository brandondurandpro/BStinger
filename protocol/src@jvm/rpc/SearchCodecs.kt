package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.SearchQuery
import org.example.kotlineditor.model.SearchUpdate

// JSON of a Find in Files request and of the end of its stream (its matches are in ProjectCodecs.kt).

/** What a Find in Files looks for. */
fun SearchQuery.toJson(): JsonObject =
    buildJsonObject {
        put("text", text)
        put("ignoreCase", ignoreCase)
    }

/** Reads what a Find in Files looks for. */
fun JsonElement.searchQuery(): SearchQuery {
    val query = jsonObject
    return SearchQuery(query.string("text"), query.bool("ignoreCase"))
}

/** End of a Find in Files stream: which engine searched, and whether it searched everything. */
fun SearchUpdate.Finished.toJson(): JsonObject =
    buildJsonObject {
        put("engine", engine.name)
        put("complete", complete)
    }

/** Reads the end of a Find in Files stream; an unknown engine (a newer server) reads as ripgrep. */
fun JsonElement.searchFinished(): SearchUpdate.Finished {
    val end = jsonObject
    val engine = SearchEngine.entries.firstOrNull { it.name == end.string("engine") } ?: SearchEngine.Ripgrep
    return SearchUpdate.Finished(engine, end.bool("complete"))
}
