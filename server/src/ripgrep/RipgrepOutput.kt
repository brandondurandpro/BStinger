package org.example.kotlineditor.server.ripgrep

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.Base64

/**
 * A line matching the query. Positions are in characters (UTF-16 units, like the editor's columns), converted from
 * the UTF-8 byte offsets ripgrep reports.
 */
internal data class RipgrepMatch(
    /** File, as ripgrep printed it (under the searched root). */
    val path: String,
    /** Zero-based line number. */
    val line: Int,
    /** Whole text of the line, without its line terminator. */
    val text: String,
    /** Every occurrence of the query in [text], in order (start inclusive, end exclusive). */
    val ranges: List<IntRange>,
)

/** One message of `rg --json`; only what the search uses is kept. */
internal sealed interface RipgrepEvent {
    /** A file with matches starts. */
    data class Begin(val path: String?) : RipgrepEvent

    /** A matching line; [match] is null when its file name is not valid UTF-8 (such a file cannot be opened). */
    data class Match(val match: RipgrepMatch?) : RipgrepEvent

    /** The matches of a file end. */
    data class End(val path: String?) : RipgrepEvent

    /** The search is over (also sent after errors on some files, before exit code 2). */
    data object Summary : RipgrepEvent

    /** Any other message (`context`, future ones). */
    data object Other : RipgrepEvent
}

/** Parser of ripgrep's JSON Lines output (`rg --json`, format documented in `rg --help`, stable since 0.10). */
internal object RipgrepJson {
    /** Parses one output line, or returns null when it is not a ripgrep message. */
    fun parse(line: String): RipgrepEvent? =
        try {
            val message = Json.parseToJsonElement(line).jsonObject
            val data = message["data"] as? JsonObject
            when (message["type"]?.jsonPrimitive?.contentOrNull) {
                "begin" -> RipgrepEvent.Begin(data?.let { text(it["path"]) })
                "match" -> RipgrepEvent.Match(data?.let(::match))
                "end" -> RipgrepEvent.End(data?.let { text(it["path"]) })
                "summary" -> RipgrepEvent.Summary
                null -> null
                else -> RipgrepEvent.Other
            }
        } catch (_: SerializationException) {
            null
        } catch (_: IllegalArgumentException) {
            // Not an object, or a field of an unexpected type.
            null
        }

    private fun match(data: JsonObject): RipgrepMatch? {
        val path = text(data["path"])
        val number = data["line_number"]?.jsonPrimitive?.intOrNull
        val raw = bytes(data["lines"])
        if (path == null || number == null || raw == null) return null
        val bytes = withoutTerminator(raw)
        val ranges =
            data["submatches"]?.jsonArray.orEmpty().mapNotNull { element ->
                val submatch = element.jsonObject
                val start = submatch["start"]?.jsonPrimitive?.intOrNull
                val end = submatch["end"]?.jsonPrimitive?.intOrNull
                if (start == null || end == null) null else charIndex(bytes, start) until charIndex(bytes, end)
            }
        return RipgrepMatch(path, number - 1, String(bytes, Charsets.UTF_8), ranges)
    }

    /**
     * Number of characters encoded by the first [byteOffset] bytes of [bytes]. Invalid UTF-8 is decoded like the
     * whole line is (one replacement character per malformed sequence), so positions stay consistent with the text.
     */
    fun charIndex(bytes: ByteArray, byteOffset: Int): Int =
        String(bytes, 0, byteOffset.coerceIn(0, bytes.size), Charsets.UTF_8).length

    // ripgrep's "arbitrary data": {"text": "..."} when valid UTF-8, {"bytes": "<base64>"} otherwise.
    private fun bytes(value: JsonElement?): ByteArray? {
        val data = value as? JsonObject
        val text = data?.get("text")?.jsonPrimitive?.contentOrNull
        val base64 = data?.get("bytes")?.jsonPrimitive?.contentOrNull
        return text?.toByteArray(Charsets.UTF_8) ?: base64?.let { Base64.getDecoder().decode(it) }
    }

    private fun text(value: JsonElement?): String? = (value as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull

    private fun withoutTerminator(bytes: ByteArray): ByteArray {
        var end = bytes.size
        if (end > 0 && bytes[end - 1] == '\n'.code.toByte()) end--
        if (end > 0 && bytes[end - 1] == '\r'.code.toByte()) end--
        return if (end == bytes.size) bytes else bytes.copyOf(end)
    }
}

/**
 * Splits a stream into UTF-8 lines, keeping at most [maxLineBytes] of memory per line: a longer line (a match in a
 * huge minified file: `--max-columns` does not apply to `--json`) is skipped and counted in [skipped].
 */
internal class JsonLineReader(private val input: InputStream, private val maxLineBytes: Int) {
    private val buffer = ByteArray(BUFFER_SIZE)
    private var position = 0
    private var limit = 0

    /** Lines dropped because they were longer than [maxLineBytes]. */
    var skipped = 0
        private set

    /** The next line without its `\n`, or null at the end of the stream. Blocks until it is complete. */
    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        var tooLong = false
        var read = false
        var newline = -1
        while (newline < 0 && fill()) {
            read = true
            newline = indexOfNewline()
            val stop = if (newline < 0) limit else newline
            tooLong = tooLong || line.size() + (stop - position) > maxLineBytes
            if (!tooLong) line.write(buffer, position, stop - position)
            position = if (newline < 0) limit else newline + 1
        }
        return if (read) finish(line, tooLong) else null
    }

    // Makes sure the buffer has unread bytes; false at the end of the stream.
    private fun fill(): Boolean {
        if (position == limit) {
            limit = input.read(buffer).coerceAtLeast(0)
            position = 0
        }
        return position < limit
    }

    private fun indexOfNewline(): Int {
        for (i in position until limit) {
            if (buffer[i] == NEWLINE) return i
        }
        return -1
    }

    private fun finish(line: ByteArrayOutputStream, tooLong: Boolean): String =
        if (tooLong) {
            skipped++
            ""
        } else {
            line.toString(Charsets.UTF_8)
        }

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
        const val NEWLINE = '\n'.code.toByte()
    }
}
