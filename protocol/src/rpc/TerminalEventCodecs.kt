package org.example.kotlineditor.rpc

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalMouseAction
import org.example.kotlineditor.model.TerminalMouseButton
import org.example.kotlineditor.model.TerminalMouseEvent

private const val TEXT = "text"
private const val KEY = "key"
private const val ACTION = "action"
private const val BUTTON = "button"
private const val SHIFT = "shift"
private const val CONTROL = "control"
private const val ALT = "alt"

/** A key event, encoded by the terminal on the server; modifiers that are not held are left out. */
fun TerminalKeyEvent.toJson(): JsonObject =
    buildJsonObject {
        put(KEY, key.name)
        put(TEXT, text)
        flag(SHIFT, shift)
        flag(CONTROL, control)
        flag(ALT, alt)
        flag("meta", meta)
        flag("repeat", repeat)
    }

/** Reads a key event written by [toJson]. */
fun JsonElement.keyEvent(): TerminalKeyEvent {
    val e = jsonObject
    val key = TerminalKey.entries.firstOrNull { it.name == e.string(KEY) } ?: error("Unknown terminal key")
    return TerminalKeyEvent(
        key = key,
        text = e.string(TEXT),
        shift = e.flag(SHIFT),
        control = e.flag(CONTROL),
        alt = e.flag(ALT),
        meta = e.flag("meta"),
        repeat = e.flag("repeat"),
    )
}

/** A mouse event, encoded by the terminal on the server; the buttons the mouse does not have are left out. */
fun TerminalMouseEvent.toJson(): JsonObject =
    buildJsonObject {
        put(ACTION, action.name)
        button?.let { put(BUTTON, it.name) }
        put("column", column)
        put("row", row)
        flag(SHIFT, shift)
        flag(CONTROL, control)
        flag(ALT, alt)
    }

/** Reads a mouse event written by [toJson]. */
fun JsonElement.mouseEvent(): TerminalMouseEvent {
    val e = jsonObject
    val action =
        TerminalMouseAction.entries.firstOrNull { it.name == e.string(ACTION) }
            ?: error("Unknown terminal mouse action")
    val button =
        e.stringOrNull(BUTTON)?.let { name ->
            TerminalMouseButton.entries.firstOrNull { it.name == name } ?: error("Unknown terminal mouse button")
        }
    return TerminalMouseEvent(
        action = action,
        button = button,
        column = e.int("column"),
        row = e.int("row"),
        shift = e.flag(SHIFT),
        control = e.flag(CONTROL),
        alt = e.flag(ALT),
    )
}

private fun JsonObjectBuilder.flag(name: String, value: Boolean) {
    if (value) put(name, true)
}

private fun JsonObject.flag(name: String): Boolean = this[name]?.let { bool(name) } ?: false
