package org.example.kotlineditor.e2e

import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.TerminalKey
import org.example.kotlineditor.model.TerminalKeyEvent
import org.example.kotlineditor.model.text
import org.example.kotlineditor.ui.Tags
import java.awt.event.InputEvent
import java.awt.event.KeyEvent.CHAR_UNDEFINED
import java.awt.event.KeyEvent.KEY_LOCATION_LEFT
import java.awt.event.KeyEvent.KEY_LOCATION_NUMPAD
import java.awt.event.KeyEvent.KEY_LOCATION_RIGHT
import java.awt.event.KeyEvent.KEY_LOCATION_STANDARD
import java.awt.event.KeyEvent.KEY_PRESSED
import java.awt.event.KeyEvent.KEY_RELEASED
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import java.awt.event.KeyEvent as AwtKeyEvent

/**
 * What the shell receives for the keys of a real keyboard, French AZERTY and US QWERTY.
 *
 * The keys are built as the platform reports them (an AWT key code, character and modifiers), so the test also
 * sees what the Compose test API never produces: the undefined character of a modifier, a lock or a function key,
 * and the layout character that Ctrl and Alt combos still carry on X11 (Alt+9 is 'ç' on AZERTY). Every press is
 * checked on what the UI sends to the shell; typing whole commands through the layouts then checks that the shell
 * itself never gets a stray character.
 */
@OptIn(ExperimentalTestApi::class)
class TerminalKeyboardE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // ----- Keyboard model ---------------------------------------------------------------------------------------

    /** A key of a layout: its AWT key code and what it types alone, with Shift, and with AltGr. */
    private class LayoutKey(val code: Int, val plain: Char, val shifted: Char, val altGr: Char? = null) {
        val isLetter: Boolean get() = plain in 'a'..'z'

        // What the terminal maps the key to (letters, digits, US punctuation); null for keys only a layout has.
        val terminalKey: TerminalKey? get() = TERMINAL_KEYS[code]

        // What AWT reports under Ctrl: the control character of a letter (Ctrl+A is 0x01), else the layout one.
        fun charUnderCtrl(shift: Boolean): Char =
            when {
                isLetter -> (plain.code and CONTROL_MASK).toChar()
                shift -> shifted
                else -> plain
            }

        // What the terminal sends under Alt: the US base of the key, the case of a letter following Shift.
        fun textUnderAlt(shift: Boolean): String =
            when {
                terminalKey == null -> ""
                isLetter && shift -> plain.uppercase()
                else -> US_BASE.getValue(code).toString()
            }
    }

    private class Layout(val name: String, val keys: List<LayoutKey>) {
        fun key(char: Char): Pair<LayoutKey, Boolean> =
            keys.firstNotNullOfOrNull { key ->
                when (char) {
                    key.plain -> key to false
                    key.shifted -> key to true
                    else -> null
                }
            } ?: error("$name has no key for '$char'")
    }

    private data class Modifier(val name: String, val code: Int, val location: Int, val mask: Int)

    // ----- Driving the keyboard ---------------------------------------------------------------------------------

    // The event the desktop app builds from the AWT one: the key from its code and location, its character as the
    // code point (undefined included), and the modifier masks. AltGr has no flag of its own there. Building it is
    // internal to Compose, but no public API sends a key with a code point of its choice.
    @OptIn(InternalComposeUiApi::class)
    private fun ComposeUiTest.awt(type: Int, code: Int, char: Char, modifiers: Int, location: Int) {
        val event =
            KeyEvent(
                key = Key(code, location),
                type = if (type == KEY_PRESSED) KeyEventType.KeyDown else KeyEventType.KeyUp,
                codePoint = char.code,
                isCtrlPressed = modifiers and InputEvent.CTRL_DOWN_MASK != 0,
                isMetaPressed = modifiers and InputEvent.META_DOWN_MASK != 0,
                isAltPressed = modifiers and InputEvent.ALT_DOWN_MASK != 0,
                isShiftPressed = modifiers and InputEvent.SHIFT_DOWN_MASK != 0,
            )
        onNodeWithTag(Tags.TERMINAL_INPUT).performKeyPress(event)
    }

    // Presses then releases one key, as the platform reports it, with the held modifiers down around it.
    private fun ComposeUiTest.chord(
        code: Int,
        char: Char,
        held: List<Modifier> = emptyList(),
        location: Int = KEY_LOCATION_STANDARD,
    ) {
        var mask = 0
        for (modifier in held) {
            mask = mask or modifier.mask
            awt(KEY_PRESSED, modifier.code, CHAR_UNDEFINED, mask, modifier.location)
        }
        awt(KEY_PRESSED, code, char, mask, location)
        awt(KEY_RELEASED, code, char, mask, location)
        for (modifier in held.reversed()) {
            mask = mask and modifier.mask.inv()
            awt(KEY_RELEASED, modifier.code, CHAR_UNDEFINED, mask, modifier.location)
        }
        waitForIdle()
    }

    // The keys the shell was sent while running the action.
    private fun ComposeUiTest.sentDuring(app: E2eApp, action: () -> Unit): List<TerminalKeyEvent> {
        app.terminalInput.clear()
        action()
        return app.terminalInput.keys
    }

    private fun List<TerminalKeyEvent>.typed(): String = joinToString("") { it.text }

    private fun label(layout: Layout, held: List<Modifier>, key: LayoutKey): String =
        "${layout.name} " + (held.map { it.name } + "'${key.plain}'").joinToString("+")

    // Types the text through the layout, one key at a time, Shift held for the shifted characters.
    private fun ComposeUiTest.type(layout: Layout, text: String) {
        for (char in text) {
            val (key, shifted) = layout.key(char)
            chord(key.code, char, if (shifted) listOf(SHIFT) else emptyList())
        }
    }

    private fun ComposeUiTest.pressEnter() = chord(AwtKeyEvent.VK_ENTER, '\n')

    private fun ComposeUiTest.openTerminal(): E2eApp {
        val app = launchWithProject(project)
        onNodeWithTag(Tags.TOOL_TERMINAL).performClick()
        awaitTag(Tags.TERMINAL_INPUT)
        onNodeWithTag(Tags.TERMINAL_INPUT).performClick()
        awaitCondition { app.project.terminal.focused }
        awaitCondition { app.screen().any(String::isNotBlank) }
        return app
    }

    private fun E2eApp.screen(): List<String> =
        project.terminal.active
            ?.snapshot
            ?.text()
            ?.map { it.trimEnd() }
            .orEmpty()

    // The command line itself contains "echo"; only its output counts.
    private fun E2eApp.printed(text: String): Boolean = screen().any { "echo" !in it && it.endsWith(text) }

    // ----- Modifiers and special keys ---------------------------------------------------------------------------

    @Test
    fun `modifier and lock keys pressed alone send nothing to the shell`() =
        runComposeUiTest {
            val app = openTerminal()
            for (key in LONE_KEYS) {
                val sent =
                    sentDuring(app) {
                        awt(KEY_PRESSED, key.code, CHAR_UNDEFINED, key.mask, key.location)
                        awt(KEY_RELEASED, key.code, CHAR_UNDEFINED, 0, key.location)
                        waitForIdle()
                    }
                assertEquals("", sent.typed(), "${key.name} alone must type nothing")
            }
            // And the shell got nothing either: the next command runs as typed, not after stray characters.
            type(QWERTY, "echo lone-keys-clean")
            pressEnter()
            awaitCondition { app.printed("lone-keys-clean") }
        }

    @Test
    fun `navigation, editing and function keys send their key, never a character`() =
        runComposeUiTest {
            val app = openTerminal()
            for ((name, code, char) in SPECIAL_KEYS) {
                for (held in listOf(emptyList(), listOf(SHIFT), listOf(CAPS_LOCK_ON))) {
                    val sent = sentDuring(app) { chord(code, char, held) }
                    val label = (held.map { it.name } + name).joinToString("+")
                    assertEquals("", sent.typed(), "$label must type nothing")
                    assertTrue(sent.any { it.key != TerminalKey.Unidentified }, "$label must still be sent")
                }
            }
        }

    // ----- Every key of both layouts ----------------------------------------------------------------------------

    @Test
    fun `AZERTY keys type the layout character, alone, with Shift and with Caps Lock`() =
        runComposeUiTest { assertLayoutTypesItsCharacters(openTerminal(), AZERTY) }

    @Test
    fun `QWERTY keys type the layout character, alone, with Shift and with Caps Lock`() =
        runComposeUiTest { assertLayoutTypesItsCharacters(openTerminal(), QWERTY) }

    private fun ComposeUiTest.assertLayoutTypesItsCharacters(app: E2eApp, layout: Layout) {
        for (key in layout.keys) {
            val alone = sentDuring(app) { chord(key.code, key.plain) }
            assertEquals(key.plain.toString(), alone.typed(), "${layout.name} '${key.plain}'")
            val shifted = sentDuring(app) { chord(key.code, key.shifted, listOf(SHIFT)) }
            assertEquals(key.shifted.toString(), shifted.typed(), "${layout.name} Shift+'${key.plain}'")
            // Caps Lock only changes the letters; it is not a modifier the shell hears about.
            val locked = if (key.isLetter) key.shifted else key.plain
            val withLock = sentDuring(app) { chord(key.code, locked, listOf(CAPS_LOCK_ON)) }
            assertEquals(locked.toString(), withLock.typed(), "${layout.name} Caps Lock+'${key.plain}'")
        }
    }

    @Test
    fun `Ctrl combos type no character on AZERTY`() =
        runComposeUiTest { assertCtrlTypesNothing(openTerminal(), AZERTY) }

    @Test
    fun `Ctrl combos type no character on QWERTY`() =
        runComposeUiTest { assertCtrlTypesNothing(openTerminal(), QWERTY) }

    // Ctrl+letter carries its control character (Ctrl+A is 0x01), Ctrl over any other key keeps the layout character
    // on X11; neither is text. Ctrl+D (end of input), Ctrl+T and Ctrl+W (terminal tabs) are left out: they would end
    // the shell or the tab under test.
    private fun ComposeUiTest.assertCtrlTypesNothing(app: E2eApp, layout: Layout) {
        val combos = listOf(listOf(CTRL), listOf(CTRL_RIGHT), listOf(CTRL, SHIFT), listOf(CAPS_LOCK_ON, CTRL))
        for (key in layout.keys.filter { it.code !in CTRL_KEYS_LEFT_OUT }) {
            for (held in combos) {
                val shift = SHIFT in held
                val sent = sentDuring(app) { chord(key.code, key.charUnderCtrl(shift), held) }
                val label = label(layout, held, key)
                assertEquals("", sent.typed(), "$label must type nothing")
                val shortcut = shift && key.code in CTRL_SHIFT_SHORTCUTS
                if (key.terminalKey != null && !shortcut) {
                    val asCtrl = sent.any { it.key == key.terminalKey && it.control }
                    assertTrue(asCtrl, "$label must reach the shell as Ctrl")
                }
            }
        }
    }

    @Test
    fun `Alt combos send the base key, not the layout character, on AZERTY`() =
        runComposeUiTest { assertAltSendsTheBaseKey(openTerminal(), AZERTY) }

    @Test
    fun `Alt combos send the base key, not the layout character, on QWERTY`() =
        runComposeUiTest { assertAltSendsTheBaseKey(openTerminal(), QWERTY) }

    // The terminal encodes Alt as ESC before the key, so the key must be the one printed on a US keyboard: Alt+9 is
    // ESC "9" on AZERTY too, never ESC "ç". Shift only changes the case of a letter; over another key the base stays
    // the unshifted one. A key a US keyboard does not have sends nothing rather than garbage.
    private fun ComposeUiTest.assertAltSendsTheBaseKey(app: E2eApp, layout: Layout) {
        for (key in layout.keys) {
            for (shift in listOf(false, true)) {
                val held = if (shift) listOf(ALT, SHIFT) else listOf(ALT)
                val sent = sentDuring(app) { chord(key.code, if (shift) key.shifted else key.plain, held) }
                val label = label(layout, held, key)
                assertEquals(key.textUnderAlt(shift), sent.typed(), label)
                assertTrue(sent.all { it.text.isEmpty() || it.alt }, "$label must be sent with Alt")
            }
        }
    }

    @Test
    fun `AltGr types the layout character, reported as AltGr on Linux and as Ctrl+Alt on Windows`() =
        runComposeUiTest {
            val app = openTerminal()
            for (key in AZERTY.keys.filter { it.altGr != null }) {
                val altGr = key.altGr!!
                for (held in listOf(listOf(ALT_GRAPH), listOf(CTRL, ALT))) {
                    val sent = sentDuring(app) { chord(key.code, altGr, held) }
                    val label = (held.map { it.name } + "'${key.plain}'").joinToString("+")
                    assertEquals(altGr.toString(), sent.typed(), "AZERTY $label types '$altGr'")
                }
            }
        }

    // ----- Whole commands through the shell ---------------------------------------------------------------------

    @Test
    fun `a command typed on AZERTY between lone modifiers runs as typed`() =
        runComposeUiTest { assertCommandRunsAsTyped(openTerminal(), AZERTY, "azerty-9,1") }

    @Test
    fun `a command typed on QWERTY between lone modifiers runs as typed`() =
        runComposeUiTest { assertCommandRunsAsTyped(openTerminal(), QWERTY, "qwerty_9+1") }

    // Every character is typed between lone presses of each modifier and lock, then upper-case letters are typed
    // with Caps Lock on: a stray byte from any of them would break the command, whatever the shell.
    private fun ComposeUiTest.assertCommandRunsAsTyped(app: E2eApp, layout: Layout, word: String) {
        type(layout, "echo ")
        for (char in word) {
            for (key in LONE_KEYS) {
                // A lock is pressed twice, to leave it as it was.
                repeat(if (key.code in LOCK_CODES) 2 else 1) {
                    awt(KEY_PRESSED, key.code, CHAR_UNDEFINED, key.mask, key.location)
                    awt(KEY_RELEASED, key.code, CHAR_UNDEFINED, 0, key.location)
                }
            }
            type(layout, char.toString())
        }
        type(layout, "-")
        chord(AwtKeyEvent.VK_CAPS_LOCK, CHAR_UNDEFINED)
        for (letter in "OK") {
            val key = layout.key(letter.lowercaseChar()).first
            chord(key.code, letter, listOf(CAPS_LOCK_ON))
        }
        chord(AwtKeyEvent.VK_CAPS_LOCK, CHAR_UNDEFINED)
        pressEnter()
        awaitCondition { app.printed("$word-OK") }
    }

    private companion object {
        // Caps Lock is a lock, not a modifier: AWT reports no mask for it, so it only shows in the characters.
        val SHIFT = Modifier("Shift", AwtKeyEvent.VK_SHIFT, KEY_LOCATION_LEFT, InputEvent.SHIFT_DOWN_MASK)
        val CTRL = Modifier("Ctrl", AwtKeyEvent.VK_CONTROL, KEY_LOCATION_LEFT, InputEvent.CTRL_DOWN_MASK)
        val CTRL_RIGHT = Modifier("RightCtrl", AwtKeyEvent.VK_CONTROL, KEY_LOCATION_RIGHT, InputEvent.CTRL_DOWN_MASK)
        val ALT = Modifier("Alt", AwtKeyEvent.VK_ALT, KEY_LOCATION_LEFT, InputEvent.ALT_DOWN_MASK)
        val ALT_GRAPH =
            Modifier("AltGr", AwtKeyEvent.VK_ALT_GRAPH, KEY_LOCATION_RIGHT, InputEvent.ALT_GRAPH_DOWN_MASK)
        val CAPS_LOCK_ON = Modifier("CapsLock", AwtKeyEvent.VK_CAPS_LOCK, KEY_LOCATION_STANDARD, 0)

        const val CONTROL_MASK = 0x1f

        val LONE_KEYS =
            listOf(
                SHIFT,
                Modifier("RightShift", AwtKeyEvent.VK_SHIFT, KEY_LOCATION_RIGHT, InputEvent.SHIFT_DOWN_MASK),
                CTRL,
                CTRL_RIGHT,
                ALT,
                ALT_GRAPH,
                Modifier("Meta", AwtKeyEvent.VK_META, KEY_LOCATION_LEFT, InputEvent.META_DOWN_MASK),
                Modifier("Windows", AwtKeyEvent.VK_WINDOWS, KEY_LOCATION_LEFT, 0),
                Modifier("ContextMenu", AwtKeyEvent.VK_CONTEXT_MENU, KEY_LOCATION_STANDARD, 0),
                CAPS_LOCK_ON,
                Modifier("NumLock", AwtKeyEvent.VK_NUM_LOCK, KEY_LOCATION_NUMPAD, 0),
                Modifier("ScrollLock", AwtKeyEvent.VK_SCROLL_LOCK, KEY_LOCATION_STANDARD, 0),
            )

        // F12 is left out: it is the key that hides the terminal. Enter, Tab and Backspace carry a control character,
        // Delete 0x7f, Escape 0x1b; the others the undefined character.
        val SPECIAL_KEYS: List<Triple<String, Int, Char>> =
            listOf(
                Triple("Escape", AwtKeyEvent.VK_ESCAPE, '\u001b'),
                Triple("Tab", AwtKeyEvent.VK_TAB, '\t'),
                Triple("Backspace", AwtKeyEvent.VK_BACK_SPACE, '\b'),
                Triple("Delete", AwtKeyEvent.VK_DELETE, '\u007f'),
                Triple("Insert", AwtKeyEvent.VK_INSERT, CHAR_UNDEFINED),
                Triple("Home", AwtKeyEvent.VK_HOME, CHAR_UNDEFINED),
                Triple("End", AwtKeyEvent.VK_END, CHAR_UNDEFINED),
                Triple("PageUp", AwtKeyEvent.VK_PAGE_UP, CHAR_UNDEFINED),
                Triple("PageDown", AwtKeyEvent.VK_PAGE_DOWN, CHAR_UNDEFINED),
                Triple("Up", AwtKeyEvent.VK_UP, CHAR_UNDEFINED),
                Triple("Down", AwtKeyEvent.VK_DOWN, CHAR_UNDEFINED),
                Triple("Left", AwtKeyEvent.VK_LEFT, CHAR_UNDEFINED),
                Triple("Right", AwtKeyEvent.VK_RIGHT, CHAR_UNDEFINED),
            ) +
                (1..11).map { Triple("F$it", AwtKeyEvent.VK_F1 + it - 1, CHAR_UNDEFINED) }

        private val LETTERS = ('a'..'z').map { LayoutKey(AwtKeyEvent.VK_A + (it - 'a'), it, it.uppercaseChar()) }

        val QWERTY =
            Layout(
                "QWERTY",
                LETTERS +
                    "0123456789".zip(")!@#$%^&*(").map { (plain, shifted) ->
                        LayoutKey(AwtKeyEvent.VK_0 + (plain - '0'), plain, shifted)
                    } +
                    listOf(
                        LayoutKey(AwtKeyEvent.VK_SPACE, ' ', ' '),
                        LayoutKey(AwtKeyEvent.VK_MINUS, '-', '_'),
                        LayoutKey(AwtKeyEvent.VK_EQUALS, '=', '+'),
                        LayoutKey(AwtKeyEvent.VK_OPEN_BRACKET, '[', '{'),
                        LayoutKey(AwtKeyEvent.VK_CLOSE_BRACKET, ']', '}'),
                        LayoutKey(AwtKeyEvent.VK_BACK_SLASH, '\\', '|'),
                        LayoutKey(AwtKeyEvent.VK_SEMICOLON, ';', ':'),
                        LayoutKey(AwtKeyEvent.VK_QUOTE, '\'', '"'),
                        LayoutKey(AwtKeyEvent.VK_COMMA, ',', '<'),
                        LayoutKey(AwtKeyEvent.VK_PERIOD, '.', '>'),
                        LayoutKey(AwtKeyEvent.VK_SLASH, '/', '?'),
                        LayoutKey(AwtKeyEvent.VK_BACK_QUOTE, '`', '~'),
                    ),
            )

        // The digit row types symbols and needs Shift for the digits; AltGr types the ASCII symbols a US keyboard
        // has on its own keys. The keys with no US equivalent (ù, ², $, ) and the others) report key codes the
        // terminal does not map.
        val AZERTY =
            Layout(
                "AZERTY",
                LETTERS +
                    listOf(
                        LayoutKey(AwtKeyEvent.VK_1, '&', '1'),
                        LayoutKey(AwtKeyEvent.VK_2, 'é', '2', '~'),
                        LayoutKey(AwtKeyEvent.VK_3, '"', '3', '#'),
                        LayoutKey(AwtKeyEvent.VK_4, '\'', '4', '{'),
                        LayoutKey(AwtKeyEvent.VK_5, '(', '5', '['),
                        LayoutKey(AwtKeyEvent.VK_6, '-', '6', '|'),
                        LayoutKey(AwtKeyEvent.VK_7, 'è', '7', '`'),
                        LayoutKey(AwtKeyEvent.VK_8, '_', '8', '\\'),
                        LayoutKey(AwtKeyEvent.VK_9, 'ç', '9', '^'),
                        LayoutKey(AwtKeyEvent.VK_0, 'à', '0', '@'),
                        LayoutKey(AwtKeyEvent.VK_RIGHT_PARENTHESIS, ')', '°', ']'),
                        LayoutKey(AwtKeyEvent.VK_EQUALS, '=', '+', '}'),
                        LayoutKey(AwtKeyEvent.VK_DOLLAR, '$', '£', '¤'),
                        LayoutKey(AwtKeyEvent.VK_UNDEFINED, 'ù', '%'),
                        LayoutKey(AwtKeyEvent.VK_ASTERISK, '*', 'µ'),
                        LayoutKey(AwtKeyEvent.VK_COMMA, ',', '?'),
                        LayoutKey(AwtKeyEvent.VK_SEMICOLON, ';', '.'),
                        LayoutKey(AwtKeyEvent.VK_COLON, ':', '/'),
                        LayoutKey(AwtKeyEvent.VK_EXCLAMATION_MARK, '!', '§'),
                        LayoutKey(AwtKeyEvent.VK_LESS, '<', '>'),
                        LayoutKey(AwtKeyEvent.VK_SPACE, ' ', ' '),
                    ),
            )

        // What each key code types on a US keyboard: the base character the terminal sends under Alt.
        val US_BASE: Map<Int, Char> = QWERTY.keys.associate { it.code to it.plain }

        val PUNCTUATION_KEYS =
            mapOf(
                ' ' to TerminalKey.Space,
                '-' to TerminalKey.Minus,
                '=' to TerminalKey.Equal,
                '[' to TerminalKey.BracketLeft,
                ']' to TerminalKey.BracketRight,
                '\\' to TerminalKey.Backslash,
                ';' to TerminalKey.Semicolon,
                '\'' to TerminalKey.Quote,
                ',' to TerminalKey.Comma,
                '.' to TerminalKey.Period,
                '/' to TerminalKey.Slash,
                '`' to TerminalKey.Backquote,
            )

        val TERMINAL_KEYS: Map<Int, TerminalKey> =
            QWERTY.keys.associate { key ->
                key.code to
                    when (key.plain) {
                        in 'a'..'z' -> TerminalKey.valueOf(key.plain.uppercase())
                        in '0'..'9' -> TerminalKey.valueOf("Digit${key.plain}")
                        else -> PUNCTUATION_KEYS.getValue(key.plain)
                    }
            }

        val CTRL_KEYS_LEFT_OUT = setOf(AwtKeyEvent.VK_D, AwtKeyEvent.VK_T, AwtKeyEvent.VK_W)

        // Ctrl+Shift+C and Ctrl+Shift+V copy and paste instead of reaching the shell.
        val CTRL_SHIFT_SHORTCUTS = setOf(AwtKeyEvent.VK_C, AwtKeyEvent.VK_V)

        val LOCK_CODES = setOf(AwtKeyEvent.VK_CAPS_LOCK, AwtKeyEvent.VK_NUM_LOCK, AwtKeyEvent.VK_SCROLL_LOCK)
    }
}
