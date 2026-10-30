package org.example.kotlineditor.rpc

import org.example.kotlineditor.model.TerminalCell
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalSnapshot

private const val HAS_FOREGROUND = 1
private const val HAS_BACKGROUND = 2
private const val HAS_ATTRIBUTES = 4
private const val HAS_TEXT = 8
private const val SHIFT = 1
private const val CONTROL = 2
private const val ALT = 4
private const val META = 8
private const val REPEAT = 16

/** Appends a complete snapshot; cells keep only what differs from the terminal defaults. */
fun WireWriter.snapshot(snapshot: TerminalSnapshot): WireWriter =
    apply {
        int(snapshot.columns)
        int(snapshot.rows)
        int(snapshot.cursorColumn)
        int(snapshot.cursorRow)
        bool(snapshot.cursorVisible)
        bool(snapshot.cursorBlinking)
        bool(snapshot.running)
        int(snapshot.exitCode ?: -1)
        long(snapshot.totalRows)
        long(snapshot.scrollOffset)
        string(snapshot.title)
        int(snapshot.lines.size)
        for (line in snapshot.lines) {
            // Trailing blank cells carry nothing: the client pads rows with blanks.
            val cells = line.dropLastWhile { it.isBlank }
            int(cells.size)
            for (cell in cells) cell(cell)
        }
    }

private fun WireWriter.cell(cell: TerminalCell) {
    // A fully blank cell in the middle of a row only needs its slot.
    if (cell.isBlank) {
        byte(0)
        return
    }
    var flags = HAS_TEXT
    if (cell.foreground != TerminalCell.DEFAULT_COLOR) flags = flags or HAS_FOREGROUND
    if (cell.background != TerminalCell.DEFAULT_COLOR) flags = flags or HAS_BACKGROUND
    if (cell.attributes != 0) flags = flags or HAS_ATTRIBUTES
    byte(flags)
    string(cell.text)
    if (flags and HAS_FOREGROUND != 0) int(cell.foreground)
    if (flags and HAS_BACKGROUND != 0) int(cell.background)
    if (flags and HAS_ATTRIBUTES != 0) int(cell.attributes)
}

/** Reads a snapshot written by [snapshot]. */
fun WireReader.snapshot(): TerminalSnapshot {
    val columns = int()
    val rows = int()
    val cursorColumn = int()
    val cursorRow = int()
    val cursorVisible = bool()
    val cursorBlinking = bool()
    val running = bool()
    val exitCode = int().takeIf { it >= 0 }
    val totalRows = long()
    val scrollOffset = long()
    val title = string()
    val lineCount = int()
    val lines = ArrayList<List<TerminalCell>>(lineCount)
    repeat(lineCount) { lines += cells() }
    return TerminalSnapshot(
        columns = columns,
        rows = rows,
        cursorColumn = cursorColumn,
        cursorRow = cursorRow,
        cursorVisible = cursorVisible,
        cursorBlinking = cursorBlinking,
        title = title,
        totalRows = totalRows,
        scrollOffset = scrollOffset,
        running = running,
        exitCode = exitCode,
        lines = lines,
    )
}

private fun WireReader.cells(): List<TerminalCell> {
    val cellCount = int()
    val cells = ArrayList<TerminalCell>(cellCount)
    repeat(cellCount) {
        val cell = cell()
        if (cell != null) cells += cell
    }
    return cells
}

private fun WireReader.cell(): TerminalCell? {
    val flags = byte()
    if (flags == 0) return null
    return TerminalCell(
        text = string(),
        foreground = if (flags and HAS_FOREGROUND != 0) int() else TerminalCell.DEFAULT_COLOR,
        background = if (flags and HAS_BACKGROUND != 0) int() else TerminalCell.DEFAULT_COLOR,
        attributes = if (flags and HAS_ATTRIBUTES != 0) int() else 0,
    )
}

/** Appends a key event, encoded by the terminal on the server. */
fun WireWriter.keyEvent(event: TerminalKeyEvent): WireWriter =
    apply {
        short(event.key.ordinal)
        var flags = 0
        if (event.shift) flags = flags or SHIFT
        if (event.control) flags = flags or CONTROL
        if (event.alt) flags = flags or ALT
        if (event.meta) flags = flags or META
        if (event.repeat) flags = flags or REPEAT
        byte(flags)
        string(event.text)
    }

/** Reads a key event written by [keyEvent]. */
fun WireReader.keyEvent(): TerminalKeyEvent {
    val key = TerminalKey.entries.getOrNull(short()) ?: error("Unknown terminal key")
    val flags = byte()
    return TerminalKeyEvent(
        key = key,
        text = string(),
        shift = flags and SHIFT != 0,
        control = flags and CONTROL != 0,
        alt = flags and ALT != 0,
        meta = flags and META != 0,
        repeat = flags and REPEAT != 0,
    )
}
