package org.example.kotlineditor.terminal.server.ghostty

import org.example.kotlineditor.model.TerminalMouseAction
import org.example.kotlineditor.model.TerminalMouseButton
import org.example.kotlineditor.model.TerminalMouseEvent
import org.example.kotlineditor.terminal.server.TerminalEngines
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the program receives when the client reports a mouse event. */
class GhosttyMouseTest {
    // Created like the server does, with the bindings of the running platform: the classpath only has Linux's.
    private fun newEngine(writePty: (ByteArray) -> Unit) = TerminalEngines.create(80, 24, 8, 16, writePty)

    private fun wheel(button: TerminalMouseButton, column: Int = 10, row: Int = 5) =
        TerminalMouseEvent(action = TerminalMouseAction.Press, button = button, column = column, row = row)

    private fun click(action: TerminalMouseAction, column: Int = 10, row: Int = 5) =
        TerminalMouseEvent(action = action, button = TerminalMouseButton.Left, column = column, row = row)

    @Test
    fun `a program without mouse tracking gets nothing`() {
        val sent = mutableListOf<ByteArray>()
        val engine = newEngine(writePty = { sent += it })
        try {
            engine.mouse(wheel(TerminalMouseButton.WheelUp))
            assertTrue(sent.isEmpty(), sent.joinToString("") { it.decodeToString() })
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a program with SGR mouse tracking gets the wheel as a mouse report`() {
        val sent = mutableListOf<ByteArray>()
        val engine = newEngine(writePty = { sent += it })
        try {
            engine.feed("\u001b[?1002h\u001b[?1006h".toByteArray(Charsets.US_ASCII))
            sent.clear()
            engine.mouse(wheel(TerminalMouseButton.WheelUp))
            assertEquals("\u001b[<64;11;6M", sent.joinToString("") { it.decodeToString() })
            sent.clear()
            engine.mouse(wheel(TerminalMouseButton.WheelDown))
            assertEquals("\u001b[<65;11;6M", sent.joinToString("") { it.decodeToString() })
        } finally {
            engine.close()
        }
    }

    @Test
    fun `mouse tracking turned off stops the reports`() {
        val sent = mutableListOf<ByteArray>()
        val engine = newEngine(writePty = { sent += it })
        try {
            engine.feed("\u001b[?1002h\u001b[?1006h".toByteArray(Charsets.US_ASCII))
            engine.feed("\u001b[?1002l\u001b[?1006l".toByteArray(Charsets.US_ASCII))
            sent.clear()
            engine.mouse(wheel(TerminalMouseButton.WheelUp))
            assertTrue(sent.isEmpty(), sent.joinToString("") { it.decodeToString() })
        } finally {
            engine.close()
        }
    }

    @Test
    fun `a program with SGR mouse tracking gets a click as a press and a release`() {
        val sent = mutableListOf<ByteArray>()
        val engine = newEngine(writePty = { sent += it })
        try {
            engine.feed("\u001b[?1000h\u001b[?1006h".toByteArray(Charsets.US_ASCII))
            sent.clear()
            engine.mouse(click(TerminalMouseAction.Press))
            assertEquals("\u001b[<0;11;6M", sent.joinToString("") { it.decodeToString() })
            sent.clear()
            engine.mouse(click(TerminalMouseAction.Release))
            assertEquals("\u001b[<0;11;6m", sent.joinToString("") { it.decodeToString() })
        } finally {
            engine.close()
        }
    }
}
