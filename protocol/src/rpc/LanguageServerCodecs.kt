package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.LanguageServerCandidateInfo
import org.example.kotlineditor.model.LanguageServerChoice
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.LanguageServersStatus
import org.example.kotlineditor.model.MemoryUsage
import org.example.kotlineditor.model.ServerMemory
import org.example.kotlineditor.model.TextChange

// JSON of the language servers: their status snapshot, the choice of a server for a language, the edits of open
// documents and the completion items.

private const val PRIVATE = "private"
private const val RESIDENT = "resident"
private const val PID = "pid"
private const val STARTED_AT = "startedAt"
private const val CPU = "cpuPercent"
private const val LANGUAGE = "language"
private const val MESSAGE = "message"
private const val ID = "id"
private const val LANGUAGE_NAME = "languageName"
private const val VERSION = "version"

/** A status snapshot. */
fun LanguageServersStatus.toJson(): JsonObject =
    buildJsonObject {
        put(VERSION, version)
        put(
            "server",
            buildJsonObject {
                put("heapUsed", server.heapUsedBytes)
                put("heapMax", server.heapMaxBytes)
                put(RESIDENT, server.residentBytes)
                put(PID, server.pid)
                put(STARTED_AT, server.startedAtMillis)
                put(CPU, server.cpuPercent)
            },
        )
        put("servers", JsonArray(servers.map { it.toJson() }))
    }

/** Reads a status snapshot. */
fun JsonElement.languageServersStatus(): LanguageServersStatus {
    val status = jsonObject
    val server = status.getValue("server").jsonObject
    return LanguageServersStatus(
        version = status.long(VERSION),
        server =
        ServerMemory(
            heapUsedBytes = server.long("heapUsed"),
            heapMaxBytes = server.long("heapMax"),
            residentBytes = server.long(RESIDENT),
            // An older server names neither: its memory is then counted as another process's.
            pid = server.longOrZero(PID),
            startedAtMillis = server.longOrZero(STARTED_AT),
            // An older server does not measure it.
            cpuPercent = if (CPU in server) server.int(CPU) else ServerMemory.NOT_MEASURED,
        ),
        servers = status.getValue("servers").jsonArray.map { it.jsonObject.languageServerInfo() },
    )
}

private fun LanguageServerInfo.toJson(): JsonObject =
    buildJsonObject {
        put(ID, id)
        put("serverName", serverName)
        put(LANGUAGE, language)
        put(LANGUAGE_NAME, languageName)
        put("root", root)
        put("state", state.name)
        put("generation", generation)
        put("pid", pid?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "memory",
            memory?.let {
                buildJsonObject {
                    put(PRIVATE, it.privateBytes)
                    put(RESIDENT, it.residentBytes)
                    put("processes", it.processCount)
                }
            } ?: JsonNull,
        )
        put("peakPrivate", peakPrivateBytes)
        put("progressPercent", progressPercent?.let(::JsonPrimitive) ?: JsonNull)
        put("progressTitle", progressTitle?.let(::JsonPrimitive) ?: JsonNull)
        put("leakVerdict", leakVerdict)
        put("leakReason", leakReason)
        put(MESSAGE, message?.let(::JsonPrimitive) ?: JsonNull)
        put("recentCrashes", recentCrashes)
        put("users", users)
        put("inlayHintsNote", inlayHintsNote?.let(::JsonPrimitive) ?: JsonNull)
        put("setupNote", setupNote?.let(::JsonPrimitive) ?: JsonNull)
    }

private fun JsonObject.languageServerInfo(): LanguageServerInfo {
    val memory = get("memory")?.takeUnless { it == JsonNull }?.jsonObject
    return LanguageServerInfo(
        id = string(ID),
        serverName = string("serverName"),
        language = string(LANGUAGE),
        languageName = string(LANGUAGE_NAME),
        root = string("root"),
        state = LanguageServerState.valueOf(string("state")),
        generation = long("generation"),
        pid = get("pid")?.takeUnless { it == JsonNull }?.jsonPrimitive?.long,
        memory = memory?.let { MemoryUsage(it.long(PRIVATE), it.long(RESIDENT), it.int("processes")) },
        peakPrivateBytes = long("peakPrivate"),
        progressPercent = get("progressPercent")?.takeUnless { it == JsonNull }?.jsonPrimitive?.int,
        progressTitle = stringOrNull("progressTitle"),
        leakVerdict = string("leakVerdict"),
        leakReason = string("leakReason"),
        message = stringOrNull(MESSAGE),
        recentCrashes = int("recentCrashes"),
        users = int("users"),
        inlayHintsNote = stringOrNull("inlayHintsNote"),
        setupNote = stringOrNull("setupNote"),
    )
}

/** The choice of a server for a language. */
fun LanguageServerChoice.languageServerChoiceJson(): JsonObject =
    buildJsonObject {
        put(LANGUAGE, language)
        put(LANGUAGE_NAME, languageName)
        put("chosen", chosen?.let(::JsonPrimitive) ?: JsonNull)
        put("disabled", disabled)
        put(
            "candidates",
            JsonArray(
                candidates.map {
                    buildJsonObject {
                        put(ID, it.id)
                        put("displayName", it.displayName)
                        put("licence", it.licence)
                        put("strengths", it.strengths.toJson())
                        put("drawbacks", it.drawbacks.toJson())
                        put("available", it.available)
                        put("details", it.details.toJson())
                        put(VERSION, it.version?.let(::JsonPrimitive) ?: JsonNull)
                        put("homepage", it.homepage?.let(::JsonPrimitive) ?: JsonNull)
                    }
                },
            ),
        )
    }

/** Reads the choice of a server for a language. */
fun JsonElement.languageServerChoice(): LanguageServerChoice {
    val choice = jsonObject
    return LanguageServerChoice(
        language = choice.string(LANGUAGE),
        languageName = choice.string(LANGUAGE_NAME),
        candidates = choice.getValue("candidates").jsonArray.map { it.jsonObject.candidateInfo() },
        chosen = choice.stringOrNull("chosen"),
        disabled = choice["disabled"]?.jsonPrimitive?.booleanOrNull == true,
    )
}

private fun JsonObject.candidateInfo() =
    LanguageServerCandidateInfo(
        id = string(ID),
        displayName = string("displayName"),
        licence = string("licence"),
        strengths = getValue("strengths").strings(),
        drawbacks = getValue("drawbacks").strings(),
        available = bool("available"),
        details = getValue("details").strings(),
        version = stringOrNull(VERSION),
        homepage = stringOrNull("homepage"),
    )

/** Edits of a document, as arrays `[startLine, startColumn, endLine, endColumn, text]`: one per keystroke. */
fun List<TextChange>.textChangesJson(): JsonArray =
    JsonArray(
        map {
            JsonArray(
                listOf(
                    JsonPrimitive(it.startLine),
                    JsonPrimitive(it.startColumn),
                    JsonPrimitive(it.endLine),
                    JsonPrimitive(it.endColumn),
                    JsonPrimitive(it.text),
                ),
            )
        },
    )

/** Reads the edits of a document. */
fun JsonElement.textChanges(): List<TextChange> =
    jsonArray.map { change ->
        val fields = change.jsonArray
        require(fields.size == CHANGE_FIELDS) { "An edit has $CHANGE_FIELDS fields" }
        TextChange(
            startLine = fields[0].jsonPrimitive.int,
            startColumn = fields[1].jsonPrimitive.int,
            endLine = fields[2].jsonPrimitive.int,
            endColumn = fields[3].jsonPrimitive.int,
            text = fields[4].jsonPrimitive.content,
        )
    }

private const val CHANGE_FIELDS = 5

// The long field [key], 0 when missing: a field a previous version of the protocol did not have.
private fun JsonObject.longOrZero(key: String): Long = if (key in this) long(key) else 0L
