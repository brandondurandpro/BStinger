package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMultiModalInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.caretCount
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TextClipboard
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val FILE = "Multi.kt"
private val CODE = listOf("val alpha = 1", "val beta = alpha", "val gamma = alpha + beta")

/** Several carets: Alt+click, Alt+J (next occurrence) and column selection (Alt+Shift+drag), like IntelliJ. */
@OptIn(ExperimentalTestApi::class)
class MultiCaretE2eTest {
    private val project = SampleProject.standard().apply { file(FILE, CODE.joinToString("\n")) }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openCode(): Document {
        val app = launchWithProject(project)
        openFromTree(FILE)
        awaitTag(EditorTags.line(2))
        return checkNotNull(
            app.project.editor.activeTab
                ?.document,
        )
    }

    private fun Document.lineList(): List<String> = lines.toList()

    @Test
    fun `Alt+click adds carets, typing and moving act at each, one undo reverts every caret`() =
        runComposeUiTest {
            val document = openCode()
            clickBefore(0, 0)
            altClickBefore(1, 0)
            altClickBefore(2, 0)
            assertEquals(3, document.caretCount)
            typeCode("x")
            assertEquals(
                listOf("xval alpha = 1", "xval beta = alpha", "xval gamma = alpha + beta"),
                document.lineList(),
            )
            editorKeys { pressKey(Key.MoveEnd) }
            typeCode(";")
            assertEquals(
                listOf("xval alpha = 1;", "xval beta = alpha;", "xval gamma = alpha + beta;"),
                document.lineList(),
            )
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            assertEquals(CODE, document.lineList())
            assertEquals(3, document.caretCount)
        }

    @Test
    fun `Escape and a plain click leave one caret`() =
        runComposeUiTest {
            val document = openCode()
            clickBefore(0, 0)
            altClickBefore(1, 0)
            editorKeys { pressKey(Key.Escape) }
            assertEquals(1, document.caretCount)
            altClickBefore(2, 0)
            assertEquals(2, document.caretCount)
            clickBefore(0, 4)
            assertEquals(1, document.caretCount)
            typeCode("y")
            assertEquals("val yalpha = 1", document.lines[0])
            assertEquals(CODE.drop(1), document.lineList().drop(1))
        }

    @Test
    fun `Alt+click on a caret removes it`() =
        runComposeUiTest {
            val document = openCode()
            clickBefore(0, 0)
            altClickBefore(1, 0)
            altClickBefore(2, 0)
            mainClock.advanceTimeBy(1_000) // not a double click
            altClickBefore(1, 0)
            assertEquals(2, document.caretCount)
            typeCode("z")
            assertEquals(listOf("zval alpha = 1", "val beta = alpha", "zval gamma = alpha + beta"), document.lineList())
        }

    @Test
    fun `Alt+J selects the word, then its next occurrences, Shift+Alt+J removes the last one`() =
        runComposeUiTest {
            val document = openCode()
            clickAt(1, 12)
            altJ()
            assertEquals("alpha", document.selectedText())
            assertEquals(1, document.caretCount)
            altJ()
            altJ()
            assertEquals(3, document.caretCount)
            // Every occurrence is selected: nothing more to add.
            altJ()
            assertEquals(3, document.caretCount)
            editorKeys { withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.J) } } }
            assertEquals(2, document.caretCount)
            typeCode("omega")
            assertEquals(listOf("val alpha = 1", "val beta = omega", "val gamma = omega + beta"), document.lineList())
        }

    @Test
    fun `Alt+J away from any word does nothing`() =
        runComposeUiTest {
            project.file(FILE, CODE.joinToString("\n") + "\n")
            val document = openCode()
            clickBefore(0, 0)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
            altJ()
            assertEquals(1, document.caretCount)
            assertEquals("", document.selectedText())
        }

    @Test
    fun `Ctrl+Alt+Shift+J selects every occurrence`() =
        runComposeUiTest {
            val document = openCode()
            clickAt(0, 6)
            editorKeys {
                withKeyDown(
                    Key.CtrlLeft,
                ) { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.J) } } }
            }
            assertEquals(3, document.caretCount)
            typeCode("a")
            assertEquals(listOf("val a = 1", "val beta = a", "val gamma = a + beta"), document.lineList())
        }

    @Test
    fun `Alt+Shift+drag selects a column, typed over on every line`() =
        runComposeUiTest {
            val document = openCode()
            clickBefore(0, 0)
            columnDrag(TextPos(0, 0), TextPos(2, 3))
            assertEquals(3, document.caretCount)
            assertEquals("val", document.selectedText())
            typeCode("var")
            assertEquals(listOf("var alpha = 1", "var beta = alpha", "var gamma = alpha + beta"), document.lineList())
            // As with one caret: the typing goes first, then the selections come back.
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            assertEquals(listOf("v alpha = 1", "v beta = alpha", "v gamma = alpha + beta"), document.lineList())
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            assertEquals(CODE, document.lineList())
            assertEquals("val", document.selectedText())
            assertEquals(3, document.caretCount)
        }

    @Test
    fun `copying at several carets copies one line each, pasting gives each caret its line`() =
        runComposeUiTest {
            val document = openCode()
            clickBefore(0, 0)
            columnDrag(TextPos(0, 0), TextPos(2, 3))
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
            assertEquals("val\nval\nval", TextClipboard.get())
            editorKeys { pressKey(Key.MoveEnd) }
            TextClipboard.set("1\n2\n3")
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.V) } }
            assertEquals(
                listOf("val alpha = 11", "val beta = alpha2", "val gamma = alpha + beta3"),
                document.lineList(),
            )
        }

    @Test
    fun `an edit at several carets saved, then undone and saved, changes only that file, then nothing`() =
        runComposeUiTest {
            project.commitAll()
            val before = DiskSnapshot.take(project)
            val document = openCode()
            clickBefore(0, 0)
            altClickBefore(2, 0)
            typeCode("x")
            save()
            awaitCondition { !document.isModified }
            before.assertUnchanged(project, except = setOf(FILE))
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            save()
            awaitCondition { !document.isModified }
            waitForIdle()
            before.assertUnchanged(project)
        }

    private fun ComposeUiTest.save() = editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }

    private fun ComposeUiTest.altJ() = editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.J) } }
}

/** Alt+click just right of the boundary before [column] of [line] (see [clickBefore]). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.altClickBefore(line: Int, column: Int) {
    val offset = boundaryOffset(line, column)
    onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).performMultiModalInput {
        key { keyDown(Key.AltLeft) }
        mouse { click(offset) }
        key { keyUp(Key.AltLeft) }
    }
    waitForIdle()
}

/** Alt+Shift+drag from the boundary before [from] to the one before [to]: a column selection. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.columnDrag(from: TextPos, to: TextPos) {
    val start = inEditor(from.line, boundaryOffset(from.line, from.column))
    val end = inEditor(to.line, boundaryOffset(to.line, to.column))
    onNodeWithTag(EditorTags.EDITOR_TEXT).performMultiModalInput {
        key { keyDown(Key.AltLeft) }
        key { keyDown(Key.ShiftLeft) }
        mouse { moveTo(start) }
        mouse { press() }
        mouse { moveTo((start + end) / 2f) }
        mouse { moveTo(end) }
        mouse { release() }
        key { keyUp(Key.ShiftLeft) }
        key { keyUp(Key.AltLeft) }
    }
    waitForIdle()
}

// Just right of the boundary before [column] of [line], in the line text node's coordinates.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.boundaryOffset(line: Int, column: Int): Offset {
    val middle = cellOffset(line, column)
    val charWidth = middle.x / (column + 0.5f)
    return Offset((column + 0.2f) * charWidth, middle.y)
}

// [offset] in the text node of [line], in the coordinates of the editor's text area.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.inEditor(line: Int, offset: Offset): Offset {
    val lineBounds =
        onNodeWithTag(EditorTags.lineText(line), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
    val editorBounds = onNodeWithTag(EditorTags.EDITOR_TEXT).fetchSemanticsNode().boundsInRoot
    return Offset(lineBounds.left - editorBounds.left + offset.x, lineBounds.top - editorBounds.top + offset.y)
}
