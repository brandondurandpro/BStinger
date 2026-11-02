@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.CodeActionInfo
import org.example.kotlineditor.model.CodeActionsResult
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.model.DiagnosticsSnapshot
import org.example.kotlineditor.model.DocumentPosition
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FileChange
import org.example.kotlineditor.model.FileDiagnostics
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.HighlightKind
import org.example.kotlineditor.model.HoverResult
import org.example.kotlineditor.model.LocationsResult
import org.example.kotlineditor.model.RenameTarget
import org.example.kotlineditor.model.SignatureHelpResult
import org.example.kotlineditor.model.SignatureInfo
import org.example.kotlineditor.model.SignatureParameter
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.SymbolHighlight
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.model.UsageCount
import org.example.kotlineditor.model.UsageCountsResult

// JSON of the code intelligence requests and answers (hover, navigation, usages, diagnostics, edits). Ranges travel
// as arrays `[startLine, startColumn, endLine, endColumn]`, the most frequent thing in these answers.

private const val STATUS = "status"
private const val RANGE = "range"
private const val PATH = "path"
private const val VERSION = "version"
private const val MESSAGE = "message"
private const val RANGE_FIELDS = 4

/** A range as `[startLine, startColumn, endLine, endColumn]`. */
fun TextRange.toJson(): JsonArray = JsonArray(listOf(startLine, startColumn, endLine, endColumn).map(::JsonPrimitive))

/** Reads a range. */
fun JsonElement.textRange(): TextRange {
    val fields = jsonArray.map { it.jsonPrimitive.int }
    require(fields.size == RANGE_FIELDS) { "A range has $RANGE_FIELDS fields" }
    val (startLine, startColumn) = fields
    val (endLine, endColumn) = fields.drop(2)
    return TextRange(startLine, startColumn, endLine, endColumn)
}

private fun JsonObject.rangeOrNull(key: String = RANGE): TextRange? = get(key)?.takeIf { it != JsonNull }?.textRange()

private fun JsonObject.status(): FeatureStatus {
    val name = string(STATUS)
    return FeatureStatus.entries.firstOrNull { it.name == name } ?: FeatureStatus.NotReady
}

private fun JsonObjectBuilder.putStatus(status: FeatureStatus) = put(STATUS, status.name)

/** The params of a request at [position]. */
fun JsonObjectBuilder.putPosition(position: DocumentPosition) {
    put(PATH, position.path)
    put(VERSION, position.version)
    put("line", position.line)
    put("column", position.column)
}

/** Reads the position of a request. */
fun JsonObject.documentPosition(): DocumentPosition =
    DocumentPosition(string(PATH), int(VERSION), int("line"), int("column"))

/** A formatting's options. */
fun FormatOptions.toJson(): JsonObject =
    buildJsonObject {
        put("tabSize", tabSize)
        put("insertSpaces", insertSpaces)
    }

/** Reads a formatting's options. */
fun JsonElement.formatOptions(): FormatOptions {
    val fields = jsonObject
    return FormatOptions(fields.int("tabSize"), fields.bool("insertSpaces"))
}

/** A hover answer. */
fun HoverResult.toJson(): JsonObject =
    buildJsonObject {
        putStatus(status)
        markdown?.let { put("markdown", it) }
        range?.let { put(RANGE, it.toJson()) }
    }

/** Reads a hover answer. */
fun JsonElement.hoverResult(): HoverResult {
    val fields = jsonObject
    return HoverResult(fields.status(), fields.stringOrNull("markdown"), fields.rangeOrNull())
}

private fun SourceLocation.toJson(): JsonObject =
    buildJsonObject {
        put(PATH, path)
        put(RANGE, range.toJson())
        preview?.let { put("preview", it) }
    }

private fun JsonObject.sourceLocation() =
    SourceLocation(string(PATH), getValue(RANGE).textRange(), stringOrNull("preview"))

/** A navigation or usages answer. */
fun LocationsResult.toJson(): JsonObject =
    buildJsonObject {
        putStatus(status)
        put("locations", JsonArray(locations.map { it.toJson() }))
        if (truncated) put("truncated", true)
    }

/** Reads a navigation or usages answer. */
fun JsonElement.locationsResult(): LocationsResult {
    val fields = jsonObject
    val locations = fields.getValue("locations").jsonArray.map { it.jsonObject.sourceLocation() }
    val truncated = fields["truncated"]?.jsonPrimitive?.content == "true"
    return LocationsResult(fields.status(), locations, truncated)
}

/** The occurrences of a symbol, as `[[startLine, startColumn, endLine, endColumn, kind]...]`. */
fun List<SymbolHighlight>.highlightsJson(): JsonArray =
    JsonArray(map { JsonArray(it.range.toJson() + JsonPrimitive(it.kind.ordinal)) })

/** Reads the occurrences of a symbol. */
fun JsonElement.symbolHighlights(): List<SymbolHighlight> =
    jsonArray.map { element ->
        val fields = element.jsonArray
        val range = JsonArray(fields.take(RANGE_FIELDS)).textRange()
        val kindIndex = fields.getOrNull(RANGE_FIELDS)?.jsonPrimitive?.int ?: 0
        SymbolHighlight(range, HighlightKind.entries.getOrElse(kindIndex) { HighlightKind.Text })
    }

/** A signature help answer. */
fun SignatureHelpResult.toJson(): JsonObject =
    buildJsonObject {
        putStatus(status)
        put("active", activeSignature)
        put("parameter", activeParameter)
        put(
            "signatures",
            JsonArray(
                signatures.map { signature ->
                    buildJsonObject {
                        put("label", signature.label)
                        signature.documentation?.let { put("doc", it) }
                        put("parameters", JsonArray(signature.parameters.map { it.toJson() }))
                    }
                },
            ),
        )
    }

private fun SignatureParameter.toJson(): JsonObject =
    buildJsonObject {
        put("start", start)
        put("end", end)
        documentation?.let { put("doc", it) }
    }

/** Reads a signature help answer. */
fun JsonElement.signatureHelpResult(): SignatureHelpResult {
    val fields = jsonObject
    val signatures =
        fields.getValue("signatures").jsonArray.map { element ->
            val signature = element.jsonObject
            val parameters =
                signature.getValue("parameters").jsonArray.map {
                    val parameter = it.jsonObject
                    SignatureParameter(parameter.int("start"), parameter.int("end"), parameter.stringOrNull("doc"))
                }
            SignatureInfo(signature.string("label"), signature.stringOrNull("doc"), parameters)
        }
    return SignatureHelpResult(fields.status(), signatures, fields.int("active"), fields.int("parameter"))
}

/** A usage counts answer. */
fun UsageCountsResult.toJson(): JsonObject =
    buildJsonObject {
        putStatus(status)
        put(
            "counts",
            JsonArray(
                counts.map {
                    buildJsonObject {
                        put(RANGE, it.range.toJson())
                        put("name", it.name)
                        put("count", it.count)
                    }
                },
            ),
        )
    }

/** Reads a usage counts answer. */
fun JsonElement.usageCountsResult(): UsageCountsResult {
    val fields = jsonObject
    val counts =
        fields.getValue("counts").jsonArray.map {
            val count = it.jsonObject
            UsageCount(count.getValue(RANGE).textRange(), count.string("name"), count.int("count"))
        }
    return UsageCountsResult(fields.status(), counts)
}

/** A diagnostics snapshot. */
fun DiagnosticsSnapshot.toJson(): JsonObject =
    buildJsonObject {
        put(VERSION, version)
        put(
            "files",
            JsonArray(
                files.map { file ->
                    buildJsonObject {
                        put(PATH, file.path)
                        put("diagnostics", JsonArray(file.diagnostics.map { it.toJson() }))
                    }
                },
            ),
        )
    }

private fun Diagnostic.toJson(): JsonObject =
    buildJsonObject {
        put(RANGE, range.toJson())
        put("severity", severity.name)
        put(MESSAGE, message)
        source?.let { put("source", it) }
        code?.let { put("code", it) }
        if (unnecessary) put("unnecessary", true)
        if (deprecated) put("deprecated", true)
    }

/** Reads a diagnostics snapshot. */
fun JsonElement.diagnosticsSnapshot(): DiagnosticsSnapshot {
    val fields = jsonObject
    val files =
        fields.getValue("files").jsonArray.map { element ->
            val file = element.jsonObject
            FileDiagnostics(
                file.string(PATH),
                file.getValue("diagnostics").jsonArray.map { it.jsonObject.diagnostic() },
            )
        }
    return DiagnosticsSnapshot(fields.long(VERSION), files)
}

private fun JsonObject.diagnostic(): Diagnostic {
    val severityName = string("severity")
    val severity = DiagnosticSeverity.entries.firstOrNull { it.name == severityName } ?: DiagnosticSeverity.Error
    return Diagnostic(
        range = getValue(RANGE).textRange(),
        severity = severity,
        message = string(MESSAGE),
        source = stringOrNull("source"),
        code = stringOrNull("code"),
        unnecessary = flag("unnecessary"),
        deprecated = flag("deprecated"),
    )
}

private fun JsonObject.flag(key: String): Boolean = get(key)?.jsonPrimitive?.content == "true"

/** An answer that edits files. */
fun EditResult.toJson(): JsonObject =
    buildJsonObject {
        putStatus(status)
        message?.let { put(MESSAGE, it) }
        put(
            "changes",
            JsonArray(
                changes.map { change ->
                    buildJsonObject {
                        put(PATH, change.path)
                        put("edits", change.edits.textChangesJson())
                        if (change.create) put("create", true)
                    }
                },
            ),
        )
    }

/** Reads an answer that edits files. */
fun JsonElement.editResult(): EditResult {
    val fields = jsonObject
    val changes =
        fields.getValue("changes").jsonArray.map { element ->
            val change = element.jsonObject
            FileChange(change.string(PATH), change.getValue("edits").textChanges(), change.flag("create"))
        }
    return EditResult(fields.status(), changes, fields.stringOrNull(MESSAGE))
}

/** A prepare rename answer. */
fun RenameTarget.toJson(): JsonObject =
    buildJsonObject {
        putStatus(status)
        range?.let { put(RANGE, it.toJson()) }
        placeholder?.let { put("placeholder", it) }
        message?.let { put(MESSAGE, it) }
    }

/** Reads a prepare rename answer. */
fun JsonElement.renameTarget(): RenameTarget {
    val fields = jsonObject
    return RenameTarget(
        fields.status(),
        fields.rangeOrNull(),
        fields.stringOrNull("placeholder"),
        fields.stringOrNull(MESSAGE),
    )
}

/** A code actions answer. */
fun CodeActionsResult.toJson(): JsonObject =
    buildJsonObject {
        putStatus(status)
        put("token", token)
        put(
            "actions",
            JsonArray(
                actions.map { action ->
                    buildJsonObject {
                        put("title", action.title)
                        action.kind?.let { put("kind", it) }
                        if (action.preferred) put("preferred", true)
                    }
                },
            ),
        )
    }

/** Reads a code actions answer. */
fun JsonElement.codeActionsResult(): CodeActionsResult {
    val fields = jsonObject
    val actions =
        fields.getValue("actions").jsonArray.map {
            val action = it.jsonObject
            CodeActionInfo(action.string("title"), action.stringOrNull("kind"), action.flag("preferred"))
        }
    return CodeActionsResult(fields.status(), fields.string("token"), actions)
}
