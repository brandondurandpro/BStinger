package org.example.kotlineditor.e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors

/**
 * The color [word] is drawn with on line [line] (the first occurrence), read from the line's semantics, which carry
 * the colored text the canvas draws. [IdeColors.Code] when it has none, `null` when the line is not on screen.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.colorOf(line: Int, word: String): Color? {
    val node =
        onAllNodes(
            hasTestTag(EditorTags.lineText(line)),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().firstOrNull()
    val text =
        node
            ?.config
            ?.getOrElseNullable(SemanticsProperties.Text) { null }
            ?.firstOrNull()
    val start = text?.text?.indexOf(word)?.takeIf { it >= 0 }
    return start?.let {
        text.spanStyles
            .lastOrNull { it.start <= start && it.end >= start + word.length }
            ?.item
            ?.color
            ?: IdeColors.Code
    }
}

/** Waits until [word] of line [line] is drawn in [color]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitColor(line: Int, word: String, color: Color, timeout: Long = TIMEOUT) {
    try {
        awaitCondition(timeout) { colorOf(line, word) == color }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("'$word' of line $line is ${colorOf(line, word)}, not $color", e)
    }
}

/**
 * Types [text] in the editor, key by key, as a user does. No shifted symbol: the e2e keyboard types `9` for Shift+9.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.typeInEditor(text: String) {
    onNodeWithTag(EditorTags.EDITOR).performKeyInput {
        for (c in text) {
            val (key, shifted) = keyOf(c)
            if (shifted) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
        }
    }
    waitForIdle()
}

/** Presses [key] in the editor, with Ctrl when [ctrl]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.pressInEditor(key: Key, ctrl: Boolean = false, times: Int = 1) {
    onNodeWithTag(EditorTags.EDITOR).performKeyInput {
        repeat(times) { if (ctrl) withKeyDown(Key.CtrlLeft) { pressKey(key) } else pressKey(key) }
    }
    waitForIdle()
}

private val LETTERS =
    listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
private val DIGITS =
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

private fun keyOf(c: Char): Pair<Key, Boolean> =
    when (c) {
        in 'a'..'z' -> LETTERS[c - 'a'] to false
        in 'A'..'Z' -> LETTERS[c - 'A'] to true
        in '0'..'9' -> DIGITS[c - '0'] to false
        ' ' -> Key.Spacebar to false
        '\n' -> Key.Enter to false
        '.' -> Key.Period to false
        ',' -> Key.Comma to false
        '=' -> Key.Equals to false
        '-' -> Key.Minus to false
        '/' -> Key.Slash to false
        else -> error("No key for '$c'")
    }
