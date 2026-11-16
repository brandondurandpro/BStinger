package org.example.kotlineditor.terminal.server.ghostty

import org.example.kotlineditor.terminal.server.TerminalEngines
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the engine reports about the screen a full-screen program owns. */
class GhosttyAltScreenTest {
    // Created like the server does, with the bindings of the running platform: the classpath only has Linux's.
    private fun newEngine(writePty: (ByteArray) -> Unit) = TerminalEngines.create(80, 24, 8, 16, writePty)

    @Test
    fun `the snapshot reports the alternate screen`() {
        val engine = newEngine(writePty = {})
        try {
            assertFalse(engine.snapshot().alternateScreen)
            engine.feed("\u001b[?1049h".toByteArray(Charsets.US_ASCII))
            assertTrue(engine.snapshot().alternateScreen, "after DECSET 1049")
            engine.feed("\u001b[?1049l".toByteArray(Charsets.US_ASCII))
            assertFalse(engine.snapshot().alternateScreen, "after DECRST 1049")
        } finally {
            engine.close()
        }
    }
}
