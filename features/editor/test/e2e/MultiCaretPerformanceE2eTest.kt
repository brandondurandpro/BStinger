package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.caretCount
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val LINES = 20_000
private const val CARETS = 2_000
private const val ALL_OCCURRENCES_BUDGET_MS = 1_000L
private const val EDIT_BUDGET_MS = 1_500L
private const val TYPING_BUDGET_MB = 100L
private const val ENTER_BUDGET_MB = 1_500L
private const val NEXT_OCCURRENCES = 50

// The first line holding "item" alone.
private const val FIRST_LINE = 9
private const val NEXT_OCCURRENCES_BUDGET_MS = 3_000L

/**
 * Carets by the thousand, through the real UI: 2,000 carets in a 20,000-line file (Ctrl+Alt+Shift+J), then typing,
 * Enter and undo at each. Each caret follows the edits of those before it once, not at each edit (which would be 4
 * million caret moves per key here). Each edit still costs a pass over the lines (the line list compares itself on
 * every write), and Enter copies the list of lines, once per caret: the budgets allow for it.
 */
@OptIn(ExperimentalTestApi::class)
class MultiCaretPerformanceE2eTest {
    private val project = SampleProject.standard()

    // "item" alone on one line in ten, CARETS times.
    private fun largeFile(): String =
        (1..LINES).joinToString(
            "\n",
        ) { if (it % (LINES / CARETS) == 0) "val item$it = item + $it" else "val v$it = $it" }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `typing, Enter and undo at 2,000 carets in a 20,000-line file`() =
        runComposeUiTest {
            project.file("large.kt", largeFile())
            val app = launchWithProject(project)
            openFromTree("large.kt")
            awaitTag(EditorTags.line(FIRST_LINE))
            val document =
                checkNotNull(
                    app.project.editor.activeTab
                        ?.document,
                )
            val original = document.lines.toList()
            // On "item" alone, after the "=".
            clickAt(FIRST_LINE, 14)
            measure("select the $CARETS occurrences of a word", ALL_OCCURRENCES_BUDGET_MS) {
                editorKeys {
                    withKeyDown(Key.CtrlLeft) {
                        withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.J) } }
                    }
                }
                renderFrame()
            }
            assertEquals(CARETS, document.caretCount)
            measure("type a key at $CARETS carets", EDIT_BUDGET_MS, TYPING_BUDGET_MB) {
                pressInEditor(Key.X)
                renderFrame()
            }
            measure("type a second key at $CARETS carets", EDIT_BUDGET_MS, TYPING_BUDGET_MB) {
                pressInEditor(Key.Y)
                renderFrame()
            }
            assertEquals("val item10 = xy + 10", document.lines[FIRST_LINE])
            assertEquals("val item$LINES = xy + $LINES", document.lines.lastOrNull())
            measure("Enter at $CARETS carets", EDIT_BUDGET_MS, ENTER_BUDGET_MB) {
                pressInEditor(Key.Enter)
                renderFrame()
            }
            assertEquals(LINES + CARETS, document.lineCount)
            assertEquals(CARETS, document.caretCount)
            measure("undo the Enter and both keys at $CARETS carets", EDIT_BUDGET_MS, ENTER_BUDGET_MB) {
                pressInEditor(Key.Z, ctrl = true, times = 3)
                renderFrame()
            }
            assertEquals(original, document.lines.toList())
        }

    @Test
    fun `Alt+J adding occurrences one at a time in a 20,000-line file`() =
        runComposeUiTest {
            project.file("large.kt", largeFile())
            val app = launchWithProject(project)
            openFromTree("large.kt")
            awaitTag(EditorTags.line(FIRST_LINE))
            val document =
                checkNotNull(
                    app.project.editor.activeTab
                        ?.document,
                )
            clickAt(FIRST_LINE, 14)
            measure("Alt+J $NEXT_OCCURRENCES times", NEXT_OCCURRENCES_BUDGET_MS) {
                repeat(NEXT_OCCURRENCES + 1) { editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.J) } } }
                renderFrame()
            }
            assertEquals(NEXT_OCCURRENCES, document.caretCount - 1)
        }
}
