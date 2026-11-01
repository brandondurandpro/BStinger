package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.ui.Tags
import org.example.kotlineditor.ui.TextClipboard

private val LETTERS =
    listOf(
        Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
        Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
    )
private val DIGITS =
    listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)

/** Sends keys to the focused editor. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.editorKeys(block: KeyInjectionScope.() -> Unit) {
    onNodeWithTag(Tags.EDITOR).performKeyInput(block)
    waitForIdle()
}

/**
 * Types [text] in the editor: letters, digits, spaces and newlines with their keys, one at a time like a user; the
 * other characters are pasted, synthetic key events of symbols carrying no character on every platform.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.typeCode(text: String) {
    for (c in text) {
        when {
            c in 'a'..'z' || c in 'A'..'Z' -> {
                val key = LETTERS[c.lowercaseChar() - 'a']
                editorKeys { if (c.isUpperCase()) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key) }
            }

            c in '0'..'9' -> {
                editorKeys { pressKey(DIGITS[c - '0']) }
            }

            c == ' ' -> {
                editorKeys { pressKey(Key.Spacebar) }
            }

            c == '\n' -> {
                editorKeys { pressKey(Key.Enter) }
            }

            else -> {
                TextClipboard.set(c.toString())
                editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            }
        }
    }
}

/** Moves the caret to the end of the document. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.caretToEnd() = editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }

/** The labels the completion popup shows, in order. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.completionLabels(): List<String> =
    onAllNodes(hasTestTagPrefix("completion.item:"), useUnmergedTree = true)
        .fetchSemanticsNodes()
        .map { node ->
            node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].removePrefix(
                "completion.item:",
            )
        }

/** Waits for the completion popup to show [label]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitCompletion(label: String, timeout: Long = SERVER_START_MS) {
    try {
        awaitCondition(timeout) { label in completionLabels() }
    } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
        throw AssertionError("No '$label' in the completion popup: ${completionLabels()}", e)
    }
}

/** Whether the popup is shown. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.completionShown(): Boolean =
    onAllNodes(hasTestTag(Tags.COMPLETION)).fetchSemanticsNodes().isNotEmpty()

/** The line [index] of [document]. */
fun Document.line(index: Int): String = lines[index]
