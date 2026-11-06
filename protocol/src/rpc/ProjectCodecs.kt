package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.FileSymbol
import org.example.kotlineditor.model.FindOptions
import org.example.kotlineditor.model.ProjectSession
import org.example.kotlineditor.model.SearchBatch
import org.example.kotlineditor.model.SearchEngine
import org.example.kotlineditor.model.SessionFile
import org.example.kotlineditor.model.TextMatch
import org.example.kotlineditor.syntax.Declaration
import org.example.kotlineditor.syntax.DeclarationKind

// JSON of project-wide data (search results, symbols, sessions).

private const val FILE = "file"
private const val LINE = "line"
private const val COLUMN = "column"

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

// The Find in Files options travel as named params on `search/findInFiles` and `search/findInFilesStream`: the
// basic search fields keep their names, so a server or a client from an earlier version keeps working.

/** Adds [options] and [limit] as the named params of a Find in Files request. */
fun JsonObjectBuilder.putParams(options: FindOptions, limit: Int) {
    put("query", options.text)
    put("ignoreCase", options.ignoreCase)
    put("wholeWord", options.wholeWord)
    put("regex", options.regex)
    if (options.filePattern != null) put(FILE_PATTERN, options.filePattern)
    if (options.scope != null) put(SCOPE, options.scope)
    put("includeIgnored", options.includeIgnored)
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

private const val WHOLE_WORD = "wholeWord"
private const val REGEX = "regex"
private const val FILE_PATTERN = "filePattern"
private const val SCOPE = "scope"
private const val INCLUDE_IGNORED = "includeIgnored"
private const val ENGINE = "engine"
private const val ERROR = "error"
private const val LIMIT = "limit"

/** One declaration. */
fun Declaration.toJson(): JsonObject =
    buildJsonObject {
        put("name", name)
        put("kind", kind.name)
        put(LINE, line)
        put(COLUMN, column)
        put("depth", depth)
    }

/** Reads one declaration. */
fun JsonElement.declaration(): Declaration {
    val d = jsonObject
    return Declaration(
        d.string("name"),
        DeclarationKind.valueOf(d.string("kind")),
        d.int(LINE),
        d.int(COLUMN),
        d.int("depth"),
    )
}

/** The declarations of a project. */
fun List<FileSymbol>.symbolsJson(): JsonArray =
    JsonArray(
        map {
            buildJsonObject {
                put(FILE, it.entry.toJson())
                put("declaration", it.declaration.toJson())
            }
        },
    )

/** Reads the declarations of a project. */
fun JsonElement.symbols(): List<FileSymbol> =
    jsonArray.map {
        val symbol = it.jsonObject
        FileSymbol(symbol.field(FILE).entry(), symbol.field("declaration").declaration())
    }

/** An optional project session, JSON null when absent. */
fun ProjectSession?.toJson(): JsonElement =
    if (this == null) {
        JsonNull
    } else {
        buildJsonObject {
            put("activeFile", activeFile)
            put(
                "openFiles",
                JsonArray(
                    openFiles.map {
                        buildJsonObject {
                            put("path", it.path)
                            put(LINE, it.line)
                            put(COLUMN, it.column)
                        }
                    },
                ),
            )
        }
    }

/** Reads an optional project session. */
fun JsonElement.session(): ProjectSession? {
    if (this == JsonNull) return null
    val session = jsonObject
    val files =
        session.field("openFiles").jsonArray.map {
            val file = it.jsonObject
            SessionFile(file.string("path"), file.int(LINE), file.int(COLUMN))
        }
    return ProjectSession(files, session.stringOrNull("activeFile"))
}
