package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.TerminalCell
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalSnapshot
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.util.Base64

// Screens are pushed up to 60 times a second, so a line is not an array of cells but of style runs: consecutive
// cells of the same style, each holding one character, travel as one string.
// - `"text"`: a run in the default style, one cell per character;
// - `{"t": "text", "f": fg, "b": bg, "a": attributes}`: a styled run (only what differs from the defaults);
// - `{"g": "text", ...}`: one cell whose text is several characters (a grapheme cluster, a surrogate pair).
// Blank cells (no text, default background) are left out, as the client pads rows with blanks.

private const val RUN = "t"
private const val TEXT = "text"
private const val DATA = "data"
private const val GRAPHEME = "g"
private const val FOREGROUND = "f"
private const val BACKGROUND = "b"
private const val ATTRIBUTES = "a"

/** A complete snapshot. */
fun TerminalSnapshot.toJson(): JsonObject =
    buildJsonObject {
        put("columns", columns)
        put("rows", rows)
        put("cursorColumn", cursorColumn)
        put("cursorRow", cursorRow)
        put("cursorVisible", cursorVisible)
        put("cursorBlinking", cursorBlinking)
        put("running", running)
        put("exitCode", exitCode)
        put("totalRows", totalRows)
        put("scrollOffset", scrollOffset)
        put("title", title)
        put("lines", JsonArray(lines.map(::lineJson)))
    }

private fun lineJson(line: List<TerminalCell>): JsonArray {
    val runs = ArrayList<JsonElement>()
    val text = StringBuilder()
    var style: TerminalCell? = null

    fun flush() {
        style?.let { runs += runJson(RUN, text.toString(), it) }
        text.clear()
        style = null
    }
    for (cell in line) {
        when {
            cell.isBlank -> {
                continue
            }

            cell.text.length != 1 -> {
                flush()
                runs += runJson(GRAPHEME, cell.text, cell)
            }

            else -> {
                if (style?.let(cell::sameStyle) == false) flush()
                if (style == null) style = cell
                text.append(cell.text)
            }
        }
    }
    flush()
    return JsonArray(runs)
}

private fun TerminalCell.sameStyle(other: TerminalCell): Boolean =
    foreground == other.foreground && background == other.background && attributes == other.attributes

private fun runJson(key: String, text: String, style: TerminalCell): JsonElement =
    if (key == RUN && style.sameStyle(TerminalCell.BLANK)) {
        JsonPrimitive(text)
    } else {
        buildJsonObject {
            put(key, text)
            if (style.foreground != TerminalCell.DEFAULT_COLOR) put(FOREGROUND, style.foreground)
            if (style.background != TerminalCell.DEFAULT_COLOR) put(BACKGROUND, style.background)
            if (style.attributes != 0) put(ATTRIBUTES, style.attributes)
        }
    }

/** Reads a snapshot written by [toJson]. */
fun JsonElement.snapshot(): TerminalSnapshot {
    val s = jsonObject
    return TerminalSnapshot(
        columns = s.int("columns"),
        rows = s.int("rows"),
        cursorColumn = s.int("cursorColumn"),
        cursorRow = s.int("cursorRow"),
        cursorVisible = s.bool("cursorVisible"),
        cursorBlinking = s.bool("cursorBlinking"),
        title = s.string("title"),
        totalRows = s.long("totalRows"),
        scrollOffset = s.long("scrollOffset"),
        running = s.bool("running"),
        exitCode = s.field("exitCode").stringOrNull()?.toInt(),
        lines = s.field("lines").jsonArray.map { it.cells() },
    )
}

private fun JsonElement.cells(): List<TerminalCell> {
    val cells = ArrayList<TerminalCell>()
    for (run in jsonArray) {
        if (run is JsonPrimitive) {
            run.content.forEach { cells += TerminalCell(it.toString()) }
            continue
        }
        val styled = run.jsonObject
        val foreground = styled[FOREGROUND]?.jsonPrimitive?.int ?: TerminalCell.DEFAULT_COLOR
        val background = styled[BACKGROUND]?.jsonPrimitive?.int ?: TerminalCell.DEFAULT_COLOR
        val attributes = styled[ATTRIBUTES]?.jsonPrimitive?.int ?: 0
        val grapheme = styled[GRAPHEME]
        if (grapheme != null) {
            cells += TerminalCell(grapheme.string(), foreground, background, attributes)
        } else {
            styled.string(RUN).forEach { cells += TerminalCell(it.toString(), foreground, background, attributes) }
        }
    }
    return cells
}

/** A key event, encoded by the terminal on the server; modifiers that are not held are left out. */
fun TerminalKeyEvent.toJson(): JsonObject =
    buildJsonObject {
        put("key", key.name)
        put(TEXT, text)
        flag("shift", shift)
        flag("control", control)
        flag("alt", alt)
        flag("meta", meta)
        flag("repeat", repeat)
    }

private fun JsonObjectBuilder.flag(name: String, value: Boolean) {
    if (value) put(name, true)
}

/** Reads a key event written by [toJson]. */
fun JsonElement.keyEvent(): TerminalKeyEvent {
    val e = jsonObject
    val key = TerminalKey.entries.firstOrNull { it.name == e.string("key") } ?: error("Unknown terminal key")

    fun flag(name: String) = e[name]?.let { e.bool(name) } ?: false
    return TerminalKeyEvent(
        key = key,
        text = e.string(TEXT),
        shift = flag("shift"),
        control = flag("control"),
        alt = flag("alt"),
        meta = flag("meta"),
        repeat = flag("repeat"),
    )
}

/** Bytes written to a terminal: typed text is valid UTF-8 and travels as a JSON string, other bytes as base64. */
fun JsonObjectBuilder.terminalInput(bytes: ByteArray) {
    val text =
        try {
            Charsets.UTF_8
                .newDecoder()
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (expected: CharacterCodingException) {
            null
        }
    if (text != null) put(TEXT, text) else put(DATA, Base64.getEncoder().encodeToString(bytes))
}

/** Reads the bytes written by [terminalInput]. */
fun JsonObject.terminalInput(): ByteArray =
    stringOrNull(TEXT)?.encodeToByteArray() ?: read(DATA) { Base64.getDecoder().decode(it.string()) }
