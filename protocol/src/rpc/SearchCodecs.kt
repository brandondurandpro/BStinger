package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.TextMatch

// JSON of Find in Files: its options and limit, its results, and the batches of a streamed search. The basic
// search fields keep the names an earlier version of the protocol sends, so an older client or server keeps
// working with the richer options on top.

private const val FILE = "file"
private const val LINE = "line"
private const val COLUMN = "column"
private const val WHOLE_WORD = "wholeWord"
private const val REGEX = "regex"
private const val FILE_PATTERN = "filePattern"
private const val SCOPE = "scope"
private const val INCLUDE_IGNORED = "includeIgnored"
private const val ENGINE = "engine"
private const val ERROR = "error"
private const val LIMIT = "limit"

/** Find in Files results. */
fun List<TextMatch>.matchesJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put(FILE, it.entry.toJson())
                put(LINE, it.line)
                put(COLUMN, it.column)
                put("text", it.lineText)
            }
        },
    )

/** Reads Find in Files results. */
fun JsonElement.matches(): List<TextMatch> =
    jsonArray.map {
        val match = it.jsonObject
        TextMatch(match.field(FILE).entry(), match.int(LINE), match.int(COLUMN), match.string("text"))
    }

/** Adds [options] and [limit] as the named params of a Find in Files request. */
fun JsonObjectBuilder.putParams(options: FindOptions, limit: Int) {
    put("query", options.text)
    put("ignoreCase", options.ignoreCase)
    put(WHOLE_WORD, options.wholeWord)
    put(REGEX, options.regex)
    if (options.filePattern != null) put(FILE_PATTERN, options.filePattern)
    if (options.scope != null) put(SCOPE, options.scope)
    put(INCLUDE_IGNORED, options.includeIgnored)
    put(LIMIT, limit)
}

/** Reads the Find in Files options of a request; the rich ones default to the plain literal search. */
fun JsonObject.findOptions(): FindOptions =
    FindOptions(
        text = string("query"),
        ignoreCase = bool("ignoreCase"),
        wholeWord = bool(WHOLE_WORD),
        regex = bool(REGEX),
        filePattern = stringOrNull(FILE_PATTERN),
        scope = stringOrNull(SCOPE),
        includeIgnored = bool(INCLUDE_IGNORED),
    )

/** One batch of a streamed Find in Files. */
fun SearchBatch.toJson(): JsonObject =
    buildJsonObject {
        put("seq", seq)
        put("hits", hits.matchesJson())
        put("done", done)
        put("total", total)
        if (engine != null) put(ENGINE, engine.name)
        if (error != null) put(ERROR, error)
    }

/** Reads a batch written by [SearchBatch.toJson]. */
fun JsonElement.searchBatch(): SearchBatch {
    val batch = jsonObject
    val engine = batch.stringOrNull(ENGINE)
    return SearchBatch(
        batch.long("seq"),
        batch.field("hits").matches(),
        batch.bool("done"),
        batch.int("total"),
        engine?.let { SearchEngine.valueOf(it) },
        batch.stringOrNull(ERROR),
    )
}
