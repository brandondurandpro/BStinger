package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class EditingE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun E2eApp.document(): Document = project.editor.activeTab!!.document!!

    private fun ComposeUiTest.keys(block: androidx.compose.ui.test.KeyInjectionScope.() -> Unit) {
        onNodeWithTag(Tags.EDITOR).performKeyInput(block)
        waitForIdle()
    }

    private fun ComposeUiTest.type(text: String) =
        keys {
            for (c in text) {
                val key = keyFor(c)
                if (c.isUpperCase() || c in SHIFTED) withKeyDown(Key.ShiftLeft) { pressKey(key) } else pressKey(key)
            }
        }

    @Test
    fun `typing inserts text at the caret and marks the tab as modified`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            type("hello ")
            assertEquals("hello # Demo", app.document().lines[0])
            awaitTag(Tags.tabModified("README.md"))
            onNodeWithTag(Tags.line(0)).assertTextContains("hello # Demo", substring = true)
            onNodeWithTag(Tags.STATUS_CARET).assertTextContains("1:7", substring = true)
        }

    @Test
    fun `Ctrl+S saves the file to disk`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            keys {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) }
                pressKey(Key.Enter)
            }
            type("saved")
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            awaitCondition { project.read("docs/guide.md").contains("saved") }
            assertEquals(textOf("Guide", "Hello from the guide", "", "saved"), project.read("docs/guide.md"))
            awaitNoTag(Tags.tabModified("docs/guide.md"))
            assertFalse(app.document().isModified)
        }

    @Test
    fun `undo and redo restore edits`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(Tags.EDITOR).performClick()
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            type("abc")
            assertEquals("abc# Demo", app.document().lines[0])
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
            assertEquals("# Demo", app.document().lines[0])
            keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
            assertEquals("abc# Demo", app.document().lines[0])
        }

    @Test
    fun `Enter keeps indentation and indents after an opening brace`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            val doc = app.document()
            // Line 6 is "fun main() {": put the caret at its end.
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.moveTo(TextPos(6, doc.lines[6].length)) }
            keys { pressKey(Key.Enter) }
            assertEquals("    ", doc.lines[7])
            assertEquals(TextPos(7, 4), doc.caret)
            type("x")
            keys { pressKey(Key.Enter) }
            assertEquals("    ", doc.lines[8])
        }

    @Test
    fun `IntelliJ editing shortcuts - duplicate, delete line, comment`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/util/Strings.kt")
            val doc = app.document()
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.moveTo(TextPos(2, 0)) }
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.D) } }
            assertEquals(doc.lines[2], doc.lines[3])
            assertEquals(5, doc.lineCount)
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
            assertEquals(4, doc.lineCount)
            runOnUiThread { doc.moveTo(TextPos(2, 0)) }
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Slash) } }
            assertTrue(doc.lines[2].startsWith("// fun String.shout()"), doc.lines[2])
            runOnUiThread { doc.moveTo(TextPos(2, 0)) }
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.Slash) } }
            assertTrue(doc.lines[2].startsWith("fun String.shout()"), doc.lines[2])
        }

    @Test
    fun `selection with Shift+arrows, copy and paste`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            val doc = app.document()
            onNodeWithTag(Tags.EDITOR).performClick()
            keys {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                withKeyDown(Key.ShiftLeft) { repeat(6) { pressKey(Key.DirectionRight) } }
            }
            assertEquals("# Demo", doc.selectedText())
            onNodeWithTag(Tags.STATUS_CARET).assertTextContains("(6 chars)", substring = true)
            keys {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.C) }
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) }
                withKeyDown(Key.CtrlLeft) { pressKey(Key.V) }
            }
            assertEquals("# Demo", doc.lines.last())
        }

    @Test
    fun `clicking in the text moves the caret and double click selects a word`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            val doc = app.document()
            val line = onNodeWithTag(Tags.line(1))
            // Past the end of the text, left of the scrollbar (which must not move the caret).
            line.performMouseInput { click(Offset(width * 0.7f, centerY)) }
            waitForIdle()
            assertEquals(1, doc.caret.line)
            mainClock.advanceTimeBy(1_000) // otherwise the next double click would count as a triple click
            line.performMouseInput { doubleClick(Offset(centerX / 2, centerY)) }
            waitForIdle()
            assertTrue(doc.selectedText().isNotEmpty() && doc.selectedText().all { it.isLetter() }, doc.selectedText())
        }

    @Test
    fun `typing at the end of a long file keeps the caret visible`() =
        runComposeUiTest {
            project.file("Long.kt", (1..3000).joinToString("\n") { "val v$it = $it" })
            val app = launchWithProject(project)
            openFromTree("Long.kt")
            onNodeWithTag(Tags.EDITOR).performClick()
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
            awaitTag(Tags.line(2999))
            type("x")
            assertEquals("val v3000 = 3000x", app.document().lines[2999])
            onNodeWithTag(Tags.line(2999)).assertTextContains("3000x", substring = true)
        }

    @Test
    fun `Alt+Shift+arrows move the current line`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            val doc = app.document()
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.moveTo(TextPos(0, 0)) }
            keys { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } } }
            assertEquals(listOf("Hello from the guide", "Guide"), doc.lines.take(2))
            keys { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionUp) } } }
            assertEquals(listOf("Guide", "Hello from the guide"), doc.lines.take(2))
        }

    @Test
    fun `Ctrl+W extends the selection and Ctrl+Shift+W shrinks it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("docs/guide.md")
            val doc = app.document()
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.moveTo(TextPos(1, 7)) }
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.W) } }
            assertEquals("from", doc.selectedText())
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.W) } }
            assertEquals("Hello from the guide", doc.selectedText())
            keys { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.W) } } }
            assertEquals("from", doc.selectedText())
        }

    @Test
    fun `Ctrl+F12 lists the file structure and jumps to a declaration`() =
        runComposeUiTest {
            project.file(
                "Shapes.kt",
                textOf(
                    "package demo",
                    "",
                    "class Circle(val r: Double) {",
                    "    fun area() = 3.14 * r * r",
                    "}",
                    "",
                    "fun perimeter(c: Circle) = 2 * 3.14 * c.r",
                    "",
                ),
            )
            val app = launchWithProject(project)
            openFromTree("Shapes.kt")
            onNodeWithTag(Tags.EDITOR).performClick()
            keys { withKeyDown(Key.CtrlLeft) { pressKey(Key.F12) } }
            awaitTag(ItemTags.structureItem("Circle", 2))
            awaitTag(ItemTags.structureItem("area", 3))
            awaitTag(Tags.STRUCTURE_INPUT).performTextInput("perim")
            awaitNoTag(ItemTags.structureItem("Circle", 2))
            onNodeWithTag(Tags.STRUCTURE_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitNoTag(Tags.STRUCTURE)
            assertEquals(TextPos(6, 4), app.document().caret)
        }

    @Test
    fun `dragging the scrollbar scrolls without selecting text or moving the caret`() =
        runComposeUiTest {
            project.file("Long.kt", (1..2000).joinToString("\n") { "val v$it = $it" })
            val app = launchWithProject(project)
            openFromTree("Long.kt")
            val tab = app.project.editor.activeTab!!
            val doc = app.document()
            onNodeWithTag(Tags.EDITOR).performClick()
            val caret = doc.caret
            onNodeWithTag(Tags.EDITOR_VERTICAL_SCROLLBAR).performMouseInput {
                moveTo(Offset(centerX, 4f))
                press()
                moveTo(Offset(centerX, height * 0.3f))
                moveTo(Offset(centerX, height * 0.6f))
                release()
            }
            waitForIdle()
            assertTrue(tab.listState.firstVisibleItemIndex > 100, "scrolled to ${tab.listState.firstVisibleItemIndex}")
            assertFalse(doc.hasSelection, "selected: ${doc.selectionRange()}")
            assertEquals(caret, doc.caret)
        }

    companion object {
        private val LETTERS =
            listOf(
                Key.A, Key.B, Key.C, Key.D, Key.E, Key.F, Key.G, Key.H, Key.I, Key.J, Key.K, Key.L, Key.M,
                Key.N, Key.O, Key.P, Key.Q, Key.R, Key.S, Key.T, Key.U, Key.V, Key.W, Key.X, Key.Y, Key.Z,
            )
        private val DIGITS =
            listOf(Key.Zero, Key.One, Key.Two, Key.Three, Key.Four, Key.Five, Key.Six, Key.Seven, Key.Eight, Key.Nine)
        private const val SHIFTED = "!@#\$%^&*()_+{}:\"<>?|~"

        private fun keyFor(c: Char): Key =
            when (c.lowercaseChar()) {
                in 'a'..'z' -> LETTERS[c.lowercaseChar() - 'a']
                in '0'..'9' -> DIGITS[c - '0']
                ' ' -> Key.Spacebar
                '.' -> Key.Period
                ',' -> Key.Comma
                '=' -> Key.Equals
                '-' -> Key.Minus
                else -> error("No key for '$c'")
            }
    }
}
