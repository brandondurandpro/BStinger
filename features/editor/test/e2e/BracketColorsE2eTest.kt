package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Brackets colored by nesting depth (#33), like VS Code: gold, orchid, blue, then gold again. A closing bracket that
 * nothing opened is red, and the brackets of strings and comments keep their color. The depth carries over from line
 * to line, and follows the edits.
 */
@OptIn(ExperimentalTestApi::class)
class BracketColorsE2eTest {
    private val project = SampleProject.standard()
    private val gold = IdeColors.BracketDepths[0]
    private val orchid = IdeColors.BracketDepths[1]
    private val blue = IdeColors.BracketDepths[2]
    private val red = IdeColors.BracketUnmatched

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun writeNested() {
        project.file(
            NESTED,
            textOf(
                "fun main() {",
                "    val xs = listOf(listOf(1), arrayOf(2))",
                "    val s = \"(not a bracket\"",
                "    // ) nor this one",
                "    if (xs.isEmpty()) {",
                "        println(s)",
                "    }",
                "}",
            ),
        )
    }

    private fun ComposeUiTest.assertNestedColors() {
        awaitBracketColors(7, listOf(gold))
        assertEquals(listOf(gold, gold, gold), bracketColors(0))
        assertEquals(listOf(orchid, blue, blue, blue, blue, orchid), bracketColors(1))
        assertEquals(listOf(orchid, blue, blue, orchid, orchid), bracketColors(4))
        assertEquals(listOf(blue, blue), bracketColors(5))
        assertEquals(listOf(orchid), bracketColors(6))
        assertEquals(IdeColors.StringLiteral, colorOf(2, "(not"), "a bracket in a string")
        assertEquals(IdeColors.Comment, colorOf(3, ") nor"), "a bracket in a comment")
    }

    @Test
    fun `brackets are colored by depth across lines, with a tree-sitter grammar`() =
        runComposeUiTest {
            writeNested()
            launchWithProject(project)
            openFromTree(NESTED)
            awaitColor(5, "println", IdeColors.FunctionDeclaration)
            assertNestedColors()
        }

    @Test
    fun `brackets are colored by depth across lines, with the line lexer`() =
        runComposeUiTest {
            writeNested()
            launchWithProject(project, grammars = null)
            openFromTree(NESTED)
            assertNestedColors()
        }

    @Test
    fun `a closing bracket that nothing opened is red, and the brackets after it count from zero`() =
        runComposeUiTest {
            project.file("src/main/kotlin/Unmatched.kt", textOf("val a = f(1))", "val b = g(h(2))"))
            launchWithProject(project, grammars = null)
            openFromTree("src/main/kotlin/Unmatched.kt")
            awaitBracketColors(0, listOf(gold, gold, red))
            awaitBracketColors(1, listOf(gold, orchid, orchid, gold))
        }

    @Test
    fun `deleting an opening bracket recolors the lines below, and undo brings their colors back`() =
        runComposeUiTest {
            writeNested()
            launchWithProject(project)
            openFromTree(NESTED)
            awaitBracketColors(7, listOf(gold))
            onNodeWithTag(EditorTags.EDITOR).performClick()
            pressInEditor(Key.MoveHome, ctrl = true)
            pressInEditor(Key.MoveEnd)
            pressInEditor(Key.Backspace)
            // `fun main() ` opens nothing any more: the lines below go one level up, and the last `}` is unmatched.
            awaitBracketColors(7, listOf(red))
            awaitBracketColors(4, listOf(gold, orchid, orchid, gold, gold))
            awaitBracketColors(6, listOf(gold))
            pressInEditor(Key.Z, ctrl = true)
            awaitBracketColors(7, listOf(gold))
            awaitBracketColors(4, listOf(orchid, blue, blue, orchid, orchid))
            awaitBracketColors(6, listOf(orchid))
        }

    @Test
    fun `the brackets of plain text are not colored`() =
        runComposeUiTest {
            project.file("notes.txt", "A list (with [brackets]) {and braces}")
            launchWithProject(project)
            openFromTree("notes.txt")
            awaitBracketColors(0, List(6) { IdeColors.Code })
        }

    @Test
    fun `coloring the brackets of a file changes nothing on disk`() =
        runComposeUiTest {
            writeNested()
            project.commitAll()
            val before = DiskSnapshot.take(project)
            launchWithProject(project)
            openFromTree(NESTED)
            awaitBracketColors(7, listOf(gold))
            before.assertUnchanged(project)
        }

    private companion object {
        const val NESTED = "src/main/kotlin/Nested.kt"
    }
}
