package org.example.kotlineditor.ui

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.utf16CodePoint
import org.example.kotlineditor.state.TerminalTab
import java.text.Normalizer

/**
 * A dead key of the layout: [spacing] is the character its event reports (a dead ˆ key types U+02C6, never
 * the ASCII `^` of a real key), [alone] is what it delivers when it composes with nothing, and [combining]
 * is the mark it composes with.
 */
internal data class DeadKey(val spacing: Char, val alone: Char, val combining: String)

// The dead keys the JDK reports through their spacing character, and what each composes with.
private val DEAD_KEYS: Map<Char, DeadKey> =
    mapOf(
        '\u02cb' to DeadKey('\u02cb', '`', "\u0300"), // ˋ grave
        '\u02ca' to DeadKey('\u02ca', '\'', "\u0301"), // ˊ acute
        '\u02c6' to DeadKey('\u02c6', '^', "\u0302"), // ˆ circumflex
        '\u02dc' to DeadKey('\u02dc', '~', "\u0303"), // ˜ tilde
        '\u00a8' to DeadKey('\u00a8', '"', "\u0308"), // ¨ diaeresis
        '\u00b8' to DeadKey('\u00b8', '¸', "\u0327"), // ¸ cedilla
        '\u02c7' to DeadKey('\u02c7', 'ˇ', "\u030c"), // ˇ caron
        '\u02dd' to DeadKey('\u02dd', '˝', "\u030b"), // ˝ double acute
        '\u02d8' to DeadKey('\u02d8', '˘', "\u0306"), // ˘ breve
        '\u02c9' to DeadKey('\u02c9', 'ˉ', "\u0304"), // ˉ macron
        '\u02da' to DeadKey('\u02da', '˚', "\u030a"), // ˚ ring above
        '\u02db' to DeadKey('\u02db', '˛', "\u0328"), // ˛ ogonek
        '\u0323' to DeadKey('\u0323', '̣', "\u0323"), // ̣ dot below
        '\u0321' to DeadKey('\u0321', '̡', "\u0321"), // ̡ horn
    )

// The keys that commit a held dead key instead of reaching the shell.
private val COMMIT_KEYS =
    setOf(Key.Spacebar, Key.Enter, Key.NumPadEnter, Key.Tab)

/**
 * The dead keys of the keyboard, held until the character they compose with arrives.
 *
 * The platform delivers a dead key as an event that types its spacing character (`ˆ`); sending it as is would
 * break composition, so the character is held, and the next key is composed with it: `ˆ` then `e` types `ê`,
 * the way the input method of a text field does. The commit keys deliver the dead character alone, in the form
 * a shell wants (`^`, not ˆ), and are not sent. `Escape` cancels the composition, and a command combo (Ctrl+C,
 * Alt+e…) commits the dead character first, then runs.
 */
internal class DeadKeys {
    private var pending: DeadKey? = null

    // Feeds one key down; returns whether the terminal used it for the composition.
    fun feed(event: KeyEvent, tab: TerminalTab): Boolean =
        when {
            hold(event, tab) -> true
            pending != null -> compose(event, checkNotNull(pending), tab)
            else -> false
        }

    // Holds a dead key when the event is one; returns whether it was.
    private fun hold(event: KeyEvent, tab: TerminalTab): Boolean {
        val dead = deadKey(event)
        if (dead == null || !plain(event)) return false
        val previous = pending
        if (previous != dead) {
            previous?.let { commit(it, tab) }
            pending = dead
        }
        return true
    }

    // A dead key only composes when typed alone: Ctrl, Alt and the Meta key make it a command of its own.
    private fun deadKey(event: KeyEvent): DeadKey? {
        if (commands(event)) return null
        return DEAD_KEYS[event.utf16CodePoint.toChar()]
    }

    private fun plain(event: KeyEvent): Boolean =
        when {
            event.isCtrlPressed -> false
            event.isMetaPressed -> false
            else -> !event.isAltPressed
        }

    private fun compose(event: KeyEvent, held: DeadKey, tab: TerminalTab): Boolean {
        pending = null
        return when {
            event.key == Key.Escape -> {
                false
            }

            event.key in COMMIT_KEYS -> {
                commit(held, tab)
                true
            }

            commands(event) -> {
                commit(held, tab)
                false
            }

            else -> {
                composeWith(event, held, tab)
            }
        }
    }

    // A command combo (Ctrl+C, Alt+e…): any modifier makes the key a command of its own.
    private fun commands(event: KeyEvent): Boolean =
        when {
            event.isCtrlPressed -> true
            event.isMetaPressed -> true
            else -> event.isAltPressed
        }

    // The character the key produces, composed with the held dead key when Unicode knows the pair, the dead
    // character alone followed by it otherwise.
    private fun composeWith(event: KeyEvent, held: DeadKey, tab: TerminalTab): Boolean {
        val base = printable(event.utf16CodePoint)
        if (base == null) {
            commit(held, tab)
            return false
        }
        val composed = Normalizer.normalize(base + held.combining, Normalizer.Form.NFC)
        tab.write(if (composed.length == 1) composed else held.alone.toString() + base)
        return true
    }

    private fun commit(held: DeadKey, tab: TerminalTab) {
        tab.write(held.alone.toString())
    }
}
