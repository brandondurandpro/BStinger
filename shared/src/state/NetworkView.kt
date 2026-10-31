package org.example.kotlineditor.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.example.kotlineditor.model.NetworkInspector
import org.example.kotlineditor.model.NetworkRecord
import java.util.Locale

private const val HEX_BYTES_PER_LINE = 16
private const val MAX_STRING = 2_000
private const val KILO = 1024.0
private const val PERCENT = 100
private val NOISE = setOf("Ping", "Pong")

/**
 * State of the Network devtools panel (Ctrl+Alt+Shift+D): the capture of [inspector] runs while the panel is shown
 * and not [paused].
 */
@Stable
class NetworkView(val inspector: NetworkInspector?) {
    var paused by mutableStateOf(false)

    /** Text searched in the kind and method of the frames. */
    var filter by mutableStateOf("")

    /** Hides the heartbeats, which otherwise fill an idle capture. */
    var hidePings by mutableStateOf(true)

    var selected: NetworkRecord? by mutableStateOf(null)

    /** The frames of [records] that the filters keep. */
    fun visible(records: List<NetworkRecord>): List<NetworkRecord> {
        val query = filter.trim()
        return records.filter { record ->
            val shown = !hidePings || record.kind !in NOISE
            val matches =
                query.isEmpty() ||
                    record.kind.contains(query, ignoreCase = true) ||
                    record.method?.contains(query, ignoreCase = true) == true
            shown && matches
        }
    }

    fun clear() {
        inspector?.clear()
        selected = null
    }
}

/** `1.5 KB`-style size. */
fun formatBytes(bytes: Long): String =
    when {
        bytes < KILO -> "$bytes B"
        bytes < KILO * KILO -> String.format(Locale.ROOT, "%.1f KB", bytes / KILO)
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / KILO / KILO)
    }

/** Sizes, latency and what was not kept of a frame, for its details. */
fun NetworkRecord.summary(): String {
    val ratio =
        if (size > 0) " (${wireSize * PERCENT / size}% on the wire)" else ""
    val parts =
        listOfNotNull(
            "${formatBytes(size.toLong())} payload, ${formatBytes(wireSize.toLong())} on the wire$ratio",
            latencyMs?.let { "answered in $it ms" },
            "payload not kept: it holds the token".takeIf { redacted },
            "payload cut at ${formatBytes(body.size.toLong())}".takeIf { truncated },
        )
    return parts.joinToString(" · ")
}

/** Classic hex dump: offset, 16 bytes in hex, the same bytes as ASCII. */
fun hexDump(bytes: ByteArray): List<String> =
    (bytes.indices step HEX_BYTES_PER_LINE).map { offset ->
        val line = bytes.copyOfRange(offset, minOf(offset + HEX_BYTES_PER_LINE, bytes.size))
        val hex = line.joinToString(" ") { String.format(Locale.ROOT, "%02x", it) }
        val ascii = line.map { byte -> byte.toInt().toChar().takeIf { it in ' '..'~' } ?: '.' }.joinToString("")
        String.format(Locale.ROOT, "%08x  %-47s  %s", offset, hex, ascii)
    }

/**
 * The JSON-RPC message of a payload, pretty-printed, one entry per line; a payload cut by the capture is not valid
 * JSON and is shown as it is. Long lines (a string holding a whole file) are cut.
 */
fun messageLines(bytes: ByteArray): List<String> {
    if (bytes.isEmpty()) return emptyList()
    val text = bytes.decodeToString()
    val pretty =
        try {
            PRETTY.encodeToString(JsonElement.serializer(), Json.parseToJsonElement(text))
        } catch (expected: SerializationException) {
            text
        }
    return pretty.lines().map { if (it.length > MAX_STRING) it.take(MAX_STRING) + "…" else it }
}

private val PRETTY = Json { prettyPrint = true }
