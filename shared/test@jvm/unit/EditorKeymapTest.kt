package org.example.kotlineditor.unit

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.ui.handleEditorKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class EditorKeymapTest {
    private fun doc() = Document(listOf(""), Language.Kotlin)

    // Compose reports keys without a character (Shift, Ctrl, function keys, …) with CHAR_UNDEFINED.
    // They must never reach the document: double Shift used to insert two U+FFFF characters.
    @Test
    fun `keys without a character do not type into the document`() {
        for (key in listOf(Key.ShiftLeft, Key.ShiftRight, Key.CtrlLeft, Key.AltLeft, Key.F5)) {
            val document = doc()
            val event = KeyEvent(key, KeyEventType.KeyDown, codePoint = 0xFFFF)
            assertFalse(handleEditorKey(event, document, 10), "key $key")
            assertEquals("", document.text(), "key $key")
        }
    }

    @Test
    fun `printable key down types its character`() {
        val document = doc()
        val event = KeyEvent(Key.A, KeyEventType.KeyDown, codePoint = 'A'.code, isShiftPressed = true)
        assertTrue(handleEditorKey(event, document, 10))
        assertEquals("A", document.text())
    }

    @Test
    fun `key up does not type`() {
        val document = doc()
        val event = KeyEvent(Key.A, KeyEventType.KeyUp, codePoint = 'a'.code)
        assertFalse(handleEditorKey(event, document, 10))
        assertEquals("", document.text())
    }

    @Test
    fun `Ctrl alone is a command but Ctrl+Alt types AltGr characters`() {
        val control = doc()
        assertFalse(
            handleEditorKey(
                KeyEvent(Key.Q, KeyEventType.KeyDown, codePoint = 'q'.code, isCtrlPressed = true),
                control,
                10,
            ),
        )
        assertEquals("", control.text())

        val altGr = doc()
        assertTrue(
            handleEditorKey(
                KeyEvent(
                    Key.Zero,
                    KeyEventType.KeyDown,
                    codePoint = '@'.code,
                    isCtrlPressed = true,
                    isAltPressed = true,
                ),
                altGr,
                10,
            ),
        )
        assertEquals("@", altGr.text())
    }
}
