/*
 * This file deliberately lives in the bindings' package: the generated `ghostty_vt_h_1` class, which holds the
 * named keys, is package-private (jextract emits it without `public`). Keeping the mapping here is the whole
 * reason for the package: everything else of the terminal engine uses the public `ghostty_vt_h` class.
 */
package io.github.vlaaad.ghostty.bindings

import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import java.lang.foreign.MemorySegment
import java.nio.charset.StandardCharsets

/** Mapping from the portable [TerminalKey] to the ids and modifiers of libghostty-vt. */
internal object GhosttyKeys {
    // DEL, which ends the printable ASCII range.
    private const val DEL = '\u007f'

    // Key id understood by `ghostty_key_event_set_key`, for a key we know.
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun keyId(key: TerminalKey): Int =
        when (key) {
            TerminalKey.A -> ghostty_vt_h_1.GHOSTTY_KEY_A()
            TerminalKey.B -> ghostty_vt_h_1.GHOSTTY_KEY_B()
            TerminalKey.C -> ghostty_vt_h_1.GHOSTTY_KEY_C()
            TerminalKey.D -> ghostty_vt_h_1.GHOSTTY_KEY_D()
            TerminalKey.E -> ghostty_vt_h_1.GHOSTTY_KEY_E()
            TerminalKey.F -> ghostty_vt_h_1.GHOSTTY_KEY_F()
            TerminalKey.G -> ghostty_vt_h_1.GHOSTTY_KEY_G()
            TerminalKey.H -> ghostty_vt_h_1.GHOSTTY_KEY_H()
            TerminalKey.I -> ghostty_vt_h_1.GHOSTTY_KEY_I()
            TerminalKey.J -> ghostty_vt_h_1.GHOSTTY_KEY_J()
            TerminalKey.K -> ghostty_vt_h_1.GHOSTTY_KEY_K()
            TerminalKey.L -> ghostty_vt_h_1.GHOSTTY_KEY_L()
            TerminalKey.M -> ghostty_vt_h_1.GHOSTTY_KEY_M()
            TerminalKey.N -> ghostty_vt_h_1.GHOSTTY_KEY_N()
            TerminalKey.O -> ghostty_vt_h_1.GHOSTTY_KEY_O()
            TerminalKey.P -> ghostty_vt_h_1.GHOSTTY_KEY_P()
            TerminalKey.Q -> ghostty_vt_h_1.GHOSTTY_KEY_Q()
            TerminalKey.R -> ghostty_vt_h_1.GHOSTTY_KEY_R()
            TerminalKey.S -> ghostty_vt_h_1.GHOSTTY_KEY_S()
            TerminalKey.T -> ghostty_vt_h_1.GHOSTTY_KEY_T()
            TerminalKey.U -> ghostty_vt_h_1.GHOSTTY_KEY_U()
            TerminalKey.V -> ghostty_vt_h_1.GHOSTTY_KEY_V()
            TerminalKey.W -> ghostty_vt_h_1.GHOSTTY_KEY_W()
            TerminalKey.X -> ghostty_vt_h_1.GHOSTTY_KEY_X()
            TerminalKey.Y -> ghostty_vt_h_1.GHOSTTY_KEY_Y()
            TerminalKey.Z -> ghostty_vt_h_1.GHOSTTY_KEY_Z()
            TerminalKey.Digit0 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_0()
            TerminalKey.Digit1 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_1()
            TerminalKey.Digit2 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_2()
            TerminalKey.Digit3 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_3()
            TerminalKey.Digit4 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_4()
            TerminalKey.Digit5 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_5()
            TerminalKey.Digit6 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_6()
            TerminalKey.Digit7 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_7()
            TerminalKey.Digit8 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_8()
            TerminalKey.Digit9 -> ghostty_vt_h_1.GHOSTTY_KEY_DIGIT_9()
            TerminalKey.Enter -> ghostty_vt_h_1.GHOSTTY_KEY_ENTER()
            TerminalKey.Tab -> ghostty_vt_h_1.GHOSTTY_KEY_TAB()
            TerminalKey.Backspace -> ghostty_vt_h_1.GHOSTTY_KEY_BACKSPACE()
            TerminalKey.Escape -> ghostty_vt_h.GHOSTTY_KEY_ESCAPE()
            TerminalKey.Space -> ghostty_vt_h_1.GHOSTTY_KEY_SPACE()
            TerminalKey.ArrowUp -> ghostty_vt_h.GHOSTTY_KEY_ARROW_UP()
            TerminalKey.ArrowDown -> ghostty_vt_h.GHOSTTY_KEY_ARROW_DOWN()
            TerminalKey.ArrowLeft -> ghostty_vt_h.GHOSTTY_KEY_ARROW_LEFT()
            TerminalKey.ArrowRight -> ghostty_vt_h.GHOSTTY_KEY_ARROW_RIGHT()
            TerminalKey.Home -> ghostty_vt_h.GHOSTTY_KEY_HOME()
            TerminalKey.End -> ghostty_vt_h_1.GHOSTTY_KEY_END()
            TerminalKey.PageUp -> ghostty_vt_h.GHOSTTY_KEY_PAGE_UP()
            TerminalKey.PageDown -> ghostty_vt_h.GHOSTTY_KEY_PAGE_DOWN()
            TerminalKey.Insert -> ghostty_vt_h.GHOSTTY_KEY_INSERT()
            TerminalKey.Delete -> ghostty_vt_h_1.GHOSTTY_KEY_DELETE()
            TerminalKey.F1 -> ghostty_vt_h.GHOSTTY_KEY_F1()
            TerminalKey.F2 -> ghostty_vt_h.GHOSTTY_KEY_F2()
            TerminalKey.F3 -> ghostty_vt_h.GHOSTTY_KEY_F3()
            TerminalKey.F4 -> ghostty_vt_h.GHOSTTY_KEY_F4()
            TerminalKey.F5 -> ghostty_vt_h.GHOSTTY_KEY_F5()
            TerminalKey.F6 -> ghostty_vt_h.GHOSTTY_KEY_F6()
            TerminalKey.F7 -> ghostty_vt_h.GHOSTTY_KEY_F7()
            TerminalKey.F8 -> ghostty_vt_h.GHOSTTY_KEY_F8()
            TerminalKey.F9 -> ghostty_vt_h.GHOSTTY_KEY_F9()
            TerminalKey.F10 -> ghostty_vt_h.GHOSTTY_KEY_F10()
            TerminalKey.F11 -> ghostty_vt_h.GHOSTTY_KEY_F11()
            TerminalKey.F12 -> ghostty_vt_h.GHOSTTY_KEY_F12()
            TerminalKey.Minus -> ghostty_vt_h_1.GHOSTTY_KEY_MINUS()
            TerminalKey.Equal -> ghostty_vt_h_1.GHOSTTY_KEY_EQUAL()
            TerminalKey.BracketLeft -> ghostty_vt_h_1.GHOSTTY_KEY_BRACKET_LEFT()
            TerminalKey.BracketRight -> ghostty_vt_h_1.GHOSTTY_KEY_BRACKET_RIGHT()
            TerminalKey.Backslash -> ghostty_vt_h_1.GHOSTTY_KEY_BACKSLASH()
            TerminalKey.Semicolon -> ghostty_vt_h_1.GHOSTTY_KEY_SEMICOLON()
            TerminalKey.Quote -> ghostty_vt_h_1.GHOSTTY_KEY_QUOTE()
            TerminalKey.Comma -> ghostty_vt_h_1.GHOSTTY_KEY_COMMA()
            TerminalKey.Period -> ghostty_vt_h_1.GHOSTTY_KEY_PERIOD()
            TerminalKey.Slash -> ghostty_vt_h_1.GHOSTTY_KEY_SLASH()
            TerminalKey.Backquote -> ghostty_vt_h_1.GHOSTTY_KEY_BACKQUOTE()
            TerminalKey.Unidentified -> ghostty_vt_h_1.GHOSTTY_KEY_UNIDENTIFIED()
        }

    /** Modifier flags of [event]. */
    fun mods(event: TerminalKeyEvent): Int {
        var mods = 0
        if (event.shift) mods = mods or ghostty_vt_h.GHOSTTY_MODS_SHIFT()
        if (event.control) mods = mods or ghostty_vt_h.GHOSTTY_MODS_CTRL()
        if (event.alt) mods = mods or ghostty_vt_h.GHOSTTY_MODS_ALT()
        if (event.meta) mods = mods or ghostty_vt_h.GHOSTTY_MODS_SUPER()
        return mods
    }

    /** Action id for a key press or an auto-repeat. */
    fun action(repeat: Boolean): Int =
        if (repeat) ghostty_vt_h_1.GHOSTTY_KEY_ACTION_REPEAT() else ghostty_vt_h_1.GHOSTTY_KEY_ACTION_PRESS()

    /**
     * Fills [event] from [key], copying its text into [textBuffer].
     *
     * The buffer belongs to the caller because libghostty does not copy the text: it must outlive the encode
     * call. Control characters are dropped, since the client sends them both as text and as [mods]; keeping them
     * would make the encoder treat Ctrl+A as a literal `0x01` text (and emit a Kitty sequence for it).
     */
    fun fill(event: MemorySegment, key: TerminalKeyEvent, textBuffer: MemorySegment) {
        val text = printable(key.text)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        if (bytes.isNotEmpty()) {
            MemorySegment.copy(MemorySegment.ofArray(bytes), 0, textBuffer, 0, bytes.size.toLong())
        }
        ghostty_vt_h.ghostty_key_event_set_key(event, keyId(key.key))
        ghostty_vt_h.ghostty_key_event_set_mods(event, mods(key).toShort())
        ghostty_vt_h.ghostty_key_event_set_consumed_mods(event, 0)
        ghostty_vt_h.ghostty_key_event_set_action(event, action(key.repeat))
        ghostty_vt_h.ghostty_key_event_set_composing(event, false)
        ghostty_vt_h.ghostty_key_event_set_utf8(
            event,
            if (bytes.isEmpty()) MemorySegment.NULL else textBuffer,
            bytes.size.toLong(),
        )
        ghostty_vt_h.ghostty_key_event_set_unshifted_codepoint(event, codepoint(key.key, text))
    }

    private fun printable(text: String): String = text.filter { it >= ' ' && it != DEL }

    // Codepoint on a US layout, which the Kitty protocol needs even when the key produced no text (Ctrl+A).
    @Suppress("CyclomaticComplexMethod")
    private fun codepoint(key: TerminalKey, text: String): Int {
        if (text.isNotEmpty()) return text.codePointAt(0)
        return when (key) {
            TerminalKey.A -> 'a'.code
            TerminalKey.B -> 'b'.code
            TerminalKey.C -> 'c'.code
            TerminalKey.D -> 'd'.code
            TerminalKey.E -> 'e'.code
            TerminalKey.F -> 'f'.code
            TerminalKey.G -> 'g'.code
            TerminalKey.H -> 'h'.code
            TerminalKey.I -> 'i'.code
            TerminalKey.J -> 'j'.code
            TerminalKey.K -> 'k'.code
            TerminalKey.L -> 'l'.code
            TerminalKey.M -> 'm'.code
            TerminalKey.N -> 'n'.code
            TerminalKey.O -> 'o'.code
            TerminalKey.P -> 'p'.code
            TerminalKey.Q -> 'q'.code
            TerminalKey.R -> 'r'.code
            TerminalKey.S -> 's'.code
            TerminalKey.T -> 't'.code
            TerminalKey.U -> 'u'.code
            TerminalKey.V -> 'v'.code
            TerminalKey.W -> 'w'.code
            TerminalKey.X -> 'x'.code
            TerminalKey.Y -> 'y'.code
            TerminalKey.Z -> 'z'.code
            TerminalKey.Digit0 -> '0'.code
            TerminalKey.Digit1 -> '1'.code
            TerminalKey.Digit2 -> '2'.code
            TerminalKey.Digit3 -> '3'.code
            TerminalKey.Digit4 -> '4'.code
            TerminalKey.Digit5 -> '5'.code
            TerminalKey.Digit6 -> '6'.code
            TerminalKey.Digit7 -> '7'.code
            TerminalKey.Digit8 -> '8'.code
            TerminalKey.Digit9 -> '9'.code
            TerminalKey.Space -> ' '.code
            TerminalKey.Minus -> '-'.code
            TerminalKey.Equal -> '='.code
            TerminalKey.BracketLeft -> '['.code
            TerminalKey.BracketRight -> ']'.code
            TerminalKey.Backslash -> '\\'.code
            TerminalKey.Semicolon -> ';'.code
            TerminalKey.Quote -> '\''.code
            TerminalKey.Comma -> ','.code
            TerminalKey.Period -> '.'.code
            TerminalKey.Slash -> '/'.code
            TerminalKey.Backquote -> '`'.code
            else -> 0
        }
    }
}
