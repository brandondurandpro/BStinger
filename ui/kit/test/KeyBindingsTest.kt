package org.example.kotlineditor.unit

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import org.example.kotlineditor.ui.KeyBinding
import org.example.kotlineditor.ui.Modifiers
import org.example.kotlineditor.ui.binding
import org.example.kotlineditor.ui.dispatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(InternalComposeUiApi::class)
class KeyBindingsTest {
    private val none = Modifiers(ctrl = false, shift = false, alt = false)
    private val ctrl = Modifiers(ctrl = true, shift = false, alt = false)
    private val ctrlShift = Modifiers(ctrl = true, shift = true, alt = false)
    private val shift = Modifiers(ctrl = false, shift = true, alt = false)
    private val alt = Modifiers(ctrl = false, shift = false, alt = true)

    private fun noAction(): Unit.(Modifiers) -> Unit = {}

    private fun keyDown(
        key: Key,
        ctrl: Boolean = false,
        meta: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ) = KeyEvent(
        key,
        KeyEventType.KeyDown,
        codePoint = 0xFFFF,
        isCtrlPressed = ctrl,
        isMetaPressed = meta,
        isShiftPressed = shift,
        isAltPressed = alt,
    )

    @Test
    fun `a binding matches only its keys`() {
        val home = binding(setOf(Key.MoveHome, Key.NumPad7), action = noAction())
        assertTrue(home.matches(Key.MoveHome, none))
        assertTrue(home.matches(Key.NumPad7, none))
        assertFalse(home.matches(Key.MoveEnd, none))
    }

    @Test
    fun `by default Ctrl and Alt must be released and Shift may be either way`() {
        val home = binding(setOf(Key.MoveHome), action = noAction())
        assertTrue(home.matches(Key.MoveHome, shift))
        assertFalse(home.matches(Key.MoveHome, ctrl))
        assertFalse(home.matches(Key.MoveHome, alt))
    }

    @Test
    fun `a required modifier must be held, a forbidden one released`() {
        val save = binding(setOf(Key.S), ctrl = true, shift = false, action = noAction())
        assertTrue(save.matches(Key.S, ctrl))
        assertFalse(save.matches(Key.S, none))
        assertFalse(save.matches(Key.S, ctrlShift))
    }

    @Test
    fun `a null modifier accepts both states`() {
        val any = binding(setOf(Key.Tab), ctrl = null, shift = null, alt = null, action = noAction())
        assertTrue(any.matches(Key.Tab, none))
        assertTrue(any.matches(Key.Tab, Modifiers(ctrl = true, shift = true, alt = true)))
    }

    @Test
    fun `Cmd counts as Ctrl`() {
        assertTrue(Modifiers(keyDown(Key.S, meta = true)).ctrl)
        assertTrue(Modifiers(keyDown(Key.S, ctrl = true)).ctrl)
        assertFalse(Modifiers(keyDown(Key.S, shift = true, alt = true)).ctrl)
    }

    @Test
    fun `dispatch runs the first matching binding on the context, with the modifiers held`() {
        val calls = mutableListOf<String>()
        val bindings: List<KeyBinding<MutableList<String>>> =
            listOf(
                binding(setOf(Key.A), ctrl = true) { held -> add("first shift=${held.shift}") },
                binding(setOf(Key.A), ctrl = null) { add("second") },
            )
        assertTrue(bindings.dispatch(keyDown(Key.A, ctrl = true, shift = true), calls))
        assertTrue(bindings.dispatch(keyDown(Key.A), calls))
        assertEquals(listOf("first shift=true", "second"), calls)
    }

    @Test
    fun `dispatch reports when no binding matches and runs nothing`() {
        val calls = mutableListOf<String>()
        val bindings = listOf(binding<MutableList<String>>(setOf(Key.A)) { add("a") })
        assertFalse(bindings.dispatch(keyDown(Key.B), calls))
        assertFalse(bindings.dispatch(keyDown(Key.A, alt = true), calls))
        assertFalse(emptyList<KeyBinding<MutableList<String>>>().dispatch(keyDown(Key.A), calls))
        assertEquals(emptyList(), calls)
    }
}
