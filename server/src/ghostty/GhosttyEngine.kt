package org.example.kotlineditor.server.ghostty

import io.github.vlaaad.ghostty.bindings.GhosttyBuffer
import io.github.vlaaad.ghostty.bindings.GhosttyColorRgb
import io.github.vlaaad.ghostty.bindings.GhosttyKeys
import io.github.vlaaad.ghostty.bindings.GhosttyMimeReader
import io.github.vlaaad.ghostty.bindings.GhosttyMimeReaderFn
import io.github.vlaaad.ghostty.bindings.GhosttyPaste
import io.github.vlaaad.ghostty.bindings.GhosttyRenderStateCursor
import io.github.vlaaad.ghostty.bindings.GhosttySizeReportSize
import io.github.vlaaad.ghostty.bindings.GhosttyString
import io.github.vlaaad.ghostty.bindings.GhosttyStyle
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollViewport
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollViewportValue
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalScrollbar
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalSizeFn
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalTitleChangedFn
import io.github.vlaaad.ghostty.bindings.GhosttyTerminalWritePtyFn
import io.github.vlaaad.ghostty.bindings.GhosttyWriter
import io.github.vlaaad.ghostty.bindings.GhosttyWriterFn
import io.github.vlaaad.ghostty.bindings.ghostty_vt_h
import org.example.kotlineditor.model.TerminalAttributes
import org.example.kotlineditor.model.TerminalCell
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.TerminalSnapshot
import java.lang.foreign.Arena
import java.lang.foreign.MemorySegment
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.foreign.ValueLayout.JAVA_SHORT
import java.nio.charset.StandardCharsets

// Longest grapheme cluster read without asking libghostty for the exact size first.
private const val GRAPHEME_BUFFER = 128

// Room for the escape sequence of one key; Kitty sequences are longer than legacy ones, but never past this.
private const val KEY_OUTPUT_BUFFER = 256

// The bytes an encoder returns when a key produces nothing.
private val NO_BYTES = ByteArray(0)

/**
 * Terminal emulator of the server: libghostty-vt parses what the PTY prints and the client draws the grid.
 *
 * One instance owns one native terminal, one render state and one key encoder. Every native call is serialized by
 * one lock because libghostty documents its handles as not thread-safe: the PTY reader thread feeds bytes while RPC
 * threads take snapshots, encode keys, resize and scroll.
 *
 * Responses the emulator produces itself (device attributes, cursor position reports, mode reports, paste framing)
 * leave through its `WRITE_PTY` callback. They are collected while the lock is held and handed to [writePty] after
 * it is released, so a slow PTY never blocks another thread behind the engine lock.
 */
@Suppress("TooManyFunctions")
internal class GhosttyEngine(
    columns: Int,
    rows: Int,
    cellWidthPx: Int,
    cellHeightPx: Int,
    scrollbackLines: Int = DEFAULT_SCROLLBACK_LINES,
    private val writePty: (ByteArray) -> Unit,
) : AutoCloseable {
    private val arena = Arena.ofShared()
    private val lock = Any()

    // Out-pointer slot shared by every `*_new` and `*_get` call; must exist before the terminal.
    private val resultSlot = arena.allocate(RESULT_SLOT_SIZE)
    private val terminal: MemorySegment = createTerminal(columns, rows)
    private val renderState =
        createHandle("render state") { out -> ghostty_vt_h.ghostty_render_state_new(MemorySegment.NULL, out) }
    private val rowIterator =
        createHandle("row iterator") { out ->
            ghostty_vt_h.ghostty_render_state_row_iterator_new(MemorySegment.NULL, out)
        }
    private val rowCells =
        createHandle("row cells") { out -> ghostty_vt_h.ghostty_render_state_row_cells_new(MemorySegment.NULL, out) }
    private val keyEncoder =
        createHandle("key encoder") { out -> ghostty_vt_h.ghostty_key_encoder_new(MemorySegment.NULL, out) }
    private val keyEvent =
        createHandle("key event") { out -> ghostty_vt_h.ghostty_key_event_new(MemorySegment.NULL, out) }
    private val titleOut = GhosttyString.allocate(arena)
    private val scrollbar = GhosttyTerminalScrollbar.allocate(arena)
    private val cursor = GhosttyRenderStateCursor.allocate(arena)
    private val scrollBehavior = GhosttyTerminalScrollViewport.allocate(arena)
    private val pasteRequest = GhosttyPaste.allocate(arena)
    private val pasteReader = arena.allocate(GhosttyMimeReader.layout())
    private val mime = arena.allocate(GhosttyString.layout())
    private val iteratorSlot = arena.allocate(ADDRESS)
    private val cellsSlot = arena.allocate(ADDRESS)
    private val numberSlot = arena.allocate(JAVA_LONG)
    private val responses = ArrayDeque<ByteArray>()

    // Scratch bytes reused by every feed; guarded by the lock.
    private var input = arena.allocate(DEFAULT_INPUT_BUFFER)

    // UTF-8 text of the key being encoded, kept alive because the key event borrows the pointer.
    private var keyText = arena.allocate(DEFAULT_KEY_TEXT)

    // Scratch bytes for the escape sequence of the key; separate from the text because the encoder reads it.
    private val keyOutput = arena.allocate(KEY_OUTPUT_BUFFER.toLong())

    // Bytes of the paste being encoded; only set while paste runs, under the lock.
    private var pasteData = MemorySegment.NULL
    private var pasteLength = 0L

    @Volatile
    private var cellWidth = cellWidthPx

    @Volatile
    private var cellHeight = cellHeightPx

    @Volatile
    private var columns = columns

    @Volatile
    private var rows = rows

    @Volatile
    private var closed = false

    init {
        GhosttyNative.ensureLoaded()
        installCallbacks()
        limitScrollback(scrollbackLines)
        GhosttyRenderStateCursor.size(cursor, GhosttyRenderStateCursor.sizeof())
        iteratorSlot.set(ADDRESS, 0, rowIterator)
        cellsSlot.set(ADDRESS, 0, rowCells)
        configurePaste()
    }

    /** Feeds what the PTY printed to the emulator, then flushes the responses it produced. */
    fun feed(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val replies =
            synchronized(lock) {
                checkOpen()
                if (input.byteSize() < bytes.size) input = arena.allocate(bytes.size.toLong())
                MemorySegment.copy(MemorySegment.ofArray(bytes), 0, input, 0, bytes.size.toLong())
                ghostty_vt_h.ghostty_terminal_vt_write(terminal, input, bytes.size.toLong())
                drainResponses()
            }
        replies.forEach(writePty)
    }

    /** Encodes [event] the way the running program expects it (legacy, `modifyOtherKeys` or Kitty keyboard). */
    fun encodeKey(event: TerminalKeyEvent) {
        val (bytes, replies) =
            synchronized(lock) {
                checkOpen()
                encode(event)
            }
        replies.forEach(writePty)
        if (bytes.isNotEmpty()) writePty(bytes)
    }

    /** Pastes [text], letting the terminal add bracketed-paste markers or turn newlines into carriage returns. */
    fun paste(text: String) {
        if (text.isEmpty()) return
        val replies =
            synchronized(lock) {
                checkOpen()
                Arena.ofConfined().use { scratch ->
                    val bytes = text.toByteArray(StandardCharsets.UTF_8)
                    pasteData = scratch.allocate(bytes.size.toLong())
                    pasteLength = bytes.size.toLong()
                    MemorySegment.copy(MemorySegment.ofArray(bytes), 0, pasteData, 0, pasteLength)
                    try {
                        GhosttyPaste.allow_unsafe(pasteRequest, true)
                        check(
                            ghostty_vt_h.ghostty_terminal_paste(terminal, pasteRequest, resultSlot),
                            "paste",
                        )
                    } finally {
                        pasteData = MemorySegment.NULL
                        pasteLength = 0
                    }
                }
                drainResponses()
            }
        replies.forEach(writePty)
    }

    /** Resizes the grid and the pixel size of a cell, reflowing the scrollback like a real terminal. */
    fun resize(columns: Int, rows: Int, cellWidthPx: Int, cellHeightPx: Int) {
        val replies =
            synchronized(lock) {
                checkOpen()
                this.columns = columns
                this.rows = rows
                cellWidth = cellWidthPx
                cellHeight = cellHeightPx
                check(
                    ghostty_vt_h.ghostty_terminal_resize(
                        terminal,
                        columns.toShort(),
                        rows.toShort(),
                        cellWidthPx,
                        cellHeightPx,
                    ),
                    "resize",
                )
                drainResponses()
            }
        replies.forEach(writePty)
    }

    /** Scrolls the viewport: positive goes up into the scrollback, as the model says. */
    fun scroll(delta: Int) {
        synchronized(lock) {
            checkOpen()
            GhosttyTerminalScrollViewport.tag(scrollBehavior, ghostty_vt_h.GHOSTTY_SCROLL_VIEWPORT_DELTA())
            GhosttyTerminalScrollViewportValue.delta(
                GhosttyTerminalScrollViewport.value(scrollBehavior),
                -delta.toLong(),
            )
            ghostty_vt_h.ghostty_terminal_scroll_viewport(terminal, scrollBehavior)
        }
    }

    /** Reads the whole visible screen. Complete state, so a client that missed one just takes the next. */
    fun snapshot(running: Boolean = true, exitCode: Int? = null): TerminalSnapshot =
        synchronized(lock) {
            checkOpen()
            check(ghostty_vt_h.ghostty_render_state_update(renderState, terminal), "render state update")
            val width = readRenderShort(ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_COLS())
            val height = readRenderShort(ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_ROWS())
            val cursor = readCursor()
            val bar = readScrollbar()
            val total = GhosttyTerminalScrollbar.total(bar)
            val visible = GhosttyTerminalScrollbar.len(bar)
            val offset = GhosttyTerminalScrollbar.offset(bar)
            val scrollOffset = (total - visible - offset).coerceAtLeast(0)
            TerminalSnapshot(
                columns = width,
                rows = height,
                cursorColumn = cursor.column,
                cursorRow = cursor.row,
                cursorVisible = cursor.visible,
                cursorBlinking = cursor.blinking,
                title = readTitle(),
                totalRows = readTotalRows(),
                scrollOffset = scrollOffset,
                running = running,
                exitCode = exitCode,
                lines = Arena.ofConfined().use { scratch -> readLines(width, scratch) },
            )
        }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            ghostty_vt_h.ghostty_render_state_free(renderState)
            ghostty_vt_h.ghostty_render_state_row_iterator_free(rowIterator)
            ghostty_vt_h.ghostty_render_state_row_cells_free(rowCells)
            ghostty_vt_h.ghostty_key_encoder_free(keyEncoder)
            ghostty_vt_h.ghostty_key_event_free(keyEvent)
            ghostty_vt_h.ghostty_terminal_free(terminal)
            arena.close()
        }
    }

    private fun createTerminal(columns: Int, rows: Int): MemorySegment {
        GhosttyNative.ensureLoaded()
        check(
            ghostty_vt_h.ghostty_terminal_new(
                MemorySegment.NULL,
                resultSlot,
                columns.toShort(),
                rows.toShort(),
            ),
            "terminal",
        )
        return resultSlot.get(ADDRESS, 0)
    }

    private fun installCallbacks() {
        val write =
            GhosttyTerminalWritePtyFn.allocate(
                GhosttyTerminalWritePtyFn.Function { _, _, data, length ->
                    if (length > 0) responses.addLast(copyBytes(data, length))
                },
                arena,
            )
        val size =
            GhosttyTerminalSizeFn.allocate(
                GhosttyTerminalSizeFn.Function { _, _, out ->
                    GhosttySizeReportSize.rows(out, rows.toShort())
                    GhosttySizeReportSize.columns(out, columns.toShort())
                    GhosttySizeReportSize.cell_width(out, cellWidth)
                    GhosttySizeReportSize.cell_height(out, cellHeight)
                    true
                },
                arena,
            )
        val title = GhosttyTerminalTitleChangedFn.allocate(GhosttyTerminalTitleChangedFn.Function { _, _ -> }, arena)
        setOption(ghostty_vt_h.GHOSTTY_TERMINAL_OPT_WRITE_PTY(), write)
        setOption(ghostty_vt_h.GHOSTTY_TERMINAL_OPT_SIZE(), size)
        setOption(ghostty_vt_h.GHOSTTY_TERMINAL_OPT_TITLE_CHANGED(), title)
    }

    private fun limitScrollback(lines: Int) {
        val value = arena.allocate(JAVA_LONG)
        value.set(JAVA_LONG, 0, lines.toLong())
        setOption(ghostty_vt_h.GHOSTTY_TERMINAL_OPT_SCROLLBACK_MAX_LINES(), value)
    }

    private fun configurePaste() {
        GhosttyPaste.size(pasteRequest, GhosttyPaste.sizeof())
        GhosttyPaste.location(pasteRequest, ghostty_vt_h.GHOSTTY_CLIPBOARD_LOCATION_STANDARD())
        GhosttyPaste.source(pasteRequest, ghostty_vt_h.GHOSTTY_PASTE_SOURCE_CLIPBOARD())
        val type = "text/plain".toByteArray(StandardCharsets.UTF_8)
        val text = arena.allocate(type.size.toLong())
        MemorySegment.copy(MemorySegment.ofArray(type), 0, text, 0, type.size.toLong())
        GhosttyString.ptr(mime, text)
        GhosttyString.len(mime, type.size.toLong())
        GhosttyPaste.mimes(pasteRequest, mime)
        GhosttyPaste.mimes_len(pasteRequest, 1)
        val read =
            GhosttyMimeReaderFn.allocate(
                GhosttyMimeReaderFn.Function { _, _, writer ->
                    val data = pasteData
                    val length = pasteLength
                    if (length == 0L) {
                        true
                    } else {
                        val sink = GhosttyWriter.write(writer)
                        GhosttyWriterFn.invoke(sink, GhosttyWriter.userdata(writer), data, length)
                    }
                },
                arena,
            )
        GhosttyMimeReader.read(pasteReader, read)
        GhosttyMimeReader.userdata(pasteReader, MemorySegment.NULL)
        GhosttyPaste.reader(pasteRequest, pasteReader)
    }

    private fun encode(event: TerminalKeyEvent): EncodedKey {
        val text = event.text.toByteArray(StandardCharsets.UTF_8)
        if (text.size > keyText.byteSize()) keyText = arena.allocate(text.size.toLong())
        GhosttyKeys.fill(keyEvent, event, keyText)
        ghostty_vt_h.ghostty_key_encoder_setopt_from_terminal(keyEncoder, terminal)
        numberSlot.set(JAVA_LONG, 0, 0)
        val first =
            ghostty_vt_h.ghostty_key_encoder_encode(keyEncoder, keyEvent, MemorySegment.NULL, 0, numberSlot)
        val needed = numberSlot.get(JAVA_LONG, 0)
        if (first == ghostty_vt_h.GHOSTTY_SUCCESS() || needed <= 0) return EncodedKey(NO_BYTES, drainResponses())
        val output = if (needed <= keyOutput.byteSize()) keyOutput else arena.allocate(needed)
        check(ghostty_vt_h.ghostty_key_encoder_encode(keyEncoder, keyEvent, output, needed, numberSlot), "key")
        return EncodedKey(copyBytes(output, numberSlot.get(JAVA_LONG, 0)), drainResponses())
    }

    private fun readLines(width: Int, scratch: Arena): List<List<TerminalCell>> {
        check(
            ghostty_vt_h.ghostty_render_state_get(
                renderState,
                ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_ROW_ITERATOR(),
                iteratorSlot,
            ),
            "row iterator",
        )
        val reader = CellReader(scratch, rowCells)
        val lines = ArrayList<List<TerminalCell>>()
        while (ghostty_vt_h.ghostty_render_state_row_iterator_next(rowIterator)) {
            check(
                ghostty_vt_h.ghostty_render_state_row_get(
                    rowIterator,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_DATA_CELLS(),
                    cellsSlot,
                ),
                "row cells",
            )
            val cells = ArrayList<TerminalCell>(width)
            for (column in 0 until width) {
                if (!ghostty_vt_h.ghostty_render_state_row_cells_next(rowCells)) break
                cells += reader.read()
            }
            lines += cells.dropLastWhile { it.isBlank }
        }
        return lines.dropLastWhile { it.isEmpty() }
    }

    private fun readCursor(): CursorState {
        check(
            ghostty_vt_h.ghostty_render_state_get(
                renderState,
                ghostty_vt_h.GHOSTTY_RENDER_STATE_DATA_CURSOR(),
                cursor,
            ),
            "cursor",
        )
        val hasValue = GhosttyRenderStateCursor.viewport_has_value(cursor)
        return CursorState(
            column = if (hasValue) GhosttyRenderStateCursor.viewport_x(cursor).toInt() else 0,
            row = if (hasValue) GhosttyRenderStateCursor.viewport_y(cursor).toInt() else 0,
            visible = GhosttyRenderStateCursor.visible(cursor),
            blinking = GhosttyRenderStateCursor.blinking(cursor),
        )
    }

    private fun readRenderShort(tag: Int): Int {
        check(ghostty_vt_h.ghostty_render_state_get(renderState, tag, resultSlot), "render state $tag")
        return resultSlot.get(JAVA_SHORT, 0).toInt()
    }

    private fun readTotalRows(): Long {
        check(
            ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_TOTAL_ROWS(), numberSlot),
            "total rows",
        )
        return numberSlot.get(JAVA_LONG, 0)
    }

    private fun readScrollbar(): MemorySegment {
        check(
            ghostty_vt_h.ghostty_terminal_get(
                terminal,
                ghostty_vt_h.GHOSTTY_TERMINAL_DATA_SCROLLBAR(),
                scrollbar,
            ),
            "scrollbar",
        )
        return scrollbar
    }

    private fun readTitle(): String {
        check(
            ghostty_vt_h.ghostty_terminal_get(terminal, ghostty_vt_h.GHOSTTY_TERMINAL_DATA_TITLE(), titleOut),
            "title",
        )
        return readString(GhosttyString.ptr(titleOut), GhosttyString.len(titleOut))
    }

    private fun drainResponses(): List<ByteArray> {
        if (responses.isEmpty()) return emptyList()
        val drained = responses.toList()
        responses.clear()
        return drained
    }

    private fun setOption(option: Int, value: MemorySegment) {
        check(ghostty_vt_h.ghostty_terminal_set(terminal, option, value), "terminal option $option")
    }

    private fun createHandle(name: String, create: (MemorySegment) -> Int): MemorySegment {
        check(create(resultSlot), name)
        return resultSlot.get(ADDRESS, 0)
    }

    private fun checkOpen() {
        check(!closed) { "The terminal is closed" }
    }

    private fun check(result: Int, what: String) {
        check(result == ghostty_vt_h.GHOSTTY_SUCCESS()) { "libghostty-vt: $what failed ($result)" }
    }

    /** Reads cells of one row; the buffers live in the snapshot's scratch arena and are reused for every cell. */
    private class CellReader(private val scratch: Arena, private val rowCells: MemorySegment) {
        private val grapheme = GhosttyBuffer.allocate(scratch)
        private val graphemeBytes = scratch.allocate(GRAPHEME_BUFFER.toLong())
        private val foreground = GhosttyColorRgb.allocate(scratch)
        private val background = GhosttyColorRgb.allocate(scratch)
        private val style =
            GhosttyStyle.allocate(scratch).also { GhosttyStyle.size(it, GhosttyStyle.sizeof()) }

        fun read(): TerminalCell {
            var text = readText()
            var fg = readColor(ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_FG_COLOR(), foreground)
            var bg = readColor(ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_BG_COLOR(), background)
            val attributes = readAttributes()
            if (attributes and INVERSE != 0) {
                val swap = fg
                fg = bg
                bg = swap
            }
            return TerminalCell(text, fg, bg, attributes and INVERSE.inv())
        }

        private fun readText(): String {
            GhosttyBuffer.ptr(grapheme, graphemeBytes)
            GhosttyBuffer.cap(grapheme, graphemeBytes.byteSize())
            GhosttyBuffer.len(grapheme, 0)
            val result =
                ghostty_vt_h.ghostty_render_state_row_cells_get(
                    rowCells,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8(),
                    grapheme,
                )
            val length = GhosttyBuffer.len(grapheme)
            if (length == 0L) return ""
            if (result == ghostty_vt_h.GHOSTTY_OUT_OF_SPACE()) {
                val grown = scratch.allocate(length)
                GhosttyBuffer.ptr(grapheme, grown)
                GhosttyBuffer.cap(grapheme, length)
                check(
                    ghostty_vt_h.ghostty_render_state_row_cells_get(
                        rowCells,
                        ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_GRAPHEMES_UTF8(),
                        grapheme,
                    ),
                    "grapheme",
                )
            } else {
                check(result, "grapheme")
            }
            return readString(GhosttyBuffer.ptr(grapheme), GhosttyBuffer.len(grapheme))
        }

        private fun readColor(tag: Int, out: MemorySegment): Int {
            if (ghostty_vt_h.ghostty_render_state_row_cells_get(rowCells, tag, out) !=
                ghostty_vt_h.GHOSTTY_SUCCESS()
            ) {
                return TerminalCell.DEFAULT_COLOR
            }
            val red = GhosttyColorRgb.r(out).toInt() and BYTE_MASK
            val green = GhosttyColorRgb.g(out).toInt() and BYTE_MASK
            val blue = GhosttyColorRgb.b(out).toInt() and BYTE_MASK
            return red.shl(RED_SHIFT) or green.shl(GREEN_SHIFT) or blue
        }

        private fun readAttributes(): Int {
            if (ghostty_vt_h.ghostty_render_state_row_cells_get(
                    rowCells,
                    ghostty_vt_h.GHOSTTY_RENDER_STATE_ROW_CELLS_DATA_STYLE(),
                    style,
                ) != ghostty_vt_h.GHOSTTY_SUCCESS()
            ) {
                return 0
            }
            var attributes = 0
            if (GhosttyStyle.bold(style)) attributes = attributes or TerminalAttributes.BOLD
            if (GhosttyStyle.faint(style)) attributes = attributes or TerminalAttributes.FAINT
            if (GhosttyStyle.italic(style)) attributes = attributes or TerminalAttributes.ITALIC
            if (GhosttyStyle.blink(style)) attributes = attributes or TerminalAttributes.BLINK
            if (GhosttyStyle.invisible(style)) attributes = attributes or TerminalAttributes.INVISIBLE
            if (GhosttyStyle.strikethrough(style)) attributes = attributes or TerminalAttributes.STRIKETHROUGH
            if (GhosttyStyle.overline(style)) attributes = attributes or TerminalAttributes.OVERLINE
            if (GhosttyStyle.underline(style) != ghostty_vt_h.GHOSTTY_SGR_UNDERLINE_NONE()) {
                attributes = attributes or TerminalAttributes.UNDERLINE
            }
            if (GhosttyStyle.inverse(style)) attributes = attributes or INVERSE
            return attributes
        }

        private fun check(result: Int, what: String) {
            check(result == ghostty_vt_h.GHOSTTY_SUCCESS()) { "libghostty-vt: $what failed ($result)" }
        }
    }

    private data class EncodedKey(val bytes: ByteArray, val replies: List<ByteArray>)

    private data class CursorState(val column: Int, val row: Int, val visible: Boolean, val blinking: Boolean)

    private companion object {
        /** Rows kept above the screen; enough to scroll back through a long build log without unbounded memory. */
        const val DEFAULT_SCROLLBACK_LINES = 10_000

        /** PTY chunks are much smaller than this; growing is only a safety net. */
        const val DEFAULT_INPUT_BUFFER = 64L * 1024

        /** Most keys carry one Unicode codepoint; a composed character may take a few more bytes. */
        const val DEFAULT_KEY_TEXT = 64L

        /** Big enough for an address, a long, or a short. */
        const val RESULT_SLOT_SIZE = 16L

        /** Reverse video is not a model attribute: libghostty reports it separately and the cell swaps the colors. */
        const val INVERSE = 1 shl 30

        const val BYTE_MASK = 0xFF

        const val RED_SHIFT = 16

        const val GREEN_SHIFT = 8
    }
}

private fun copyBytes(pointer: MemorySegment, length: Long): ByteArray {
    val bytes = ByteArray(length.toInt())
    MemorySegment.copy(pointer, 0, MemorySegment.ofArray(bytes), 0, length)
    return bytes
}

private fun readString(pointer: MemorySegment, length: Long): String {
    if (length <= 0) return ""
    val bytes = ByteArray(length.toInt())
    MemorySegment.copy(pointer, 0, MemorySegment.ofArray(bytes), 0, length)
    return String(bytes, StandardCharsets.UTF_8)
}
