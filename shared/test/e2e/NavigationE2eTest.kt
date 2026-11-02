package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.selectedText
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NavigationE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `Ctrl+R replaces all occurrences in one undoable step`() =
        runComposeUiTest {
            project.file("Rep.kt", "val a = old\nval b = old + old\n")
            val app = launchWithProject(project)
            openFromTree("Rep.kt")
            val doc =
                app.project.editor.activeTab!!
                    .document!!
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.R) } }
            awaitTag(EditorTags.FIND_BAR_INPUT).performTextInput("old")
            awaitCondition { app.project.find.matches.size == 3 }
            awaitTag(EditorTags.REPLACE_INPUT).performTextInput("new")
            onNodeWithTag(EditorTags.REPLACE_ALL).performClick()
            awaitCondition { doc.text() == "val a = new\nval b = new + new\n" }
            awaitCondition {
                app.project.find.matches
                    .isEmpty()
            }
            runOnUiThread { doc.undo() }
            assertEquals("val a = old\nval b = old + old\n", doc.text())
        }

    @Test
    fun `Replace replaces the current occurrence and selects the next one`() =
        runComposeUiTest {
            project.file("Rep.kt", "x1 x2 x3\n")
            val app = launchWithProject(project)
            openFromTree("Rep.kt")
            val doc =
                app.project.editor.activeTab!!
                    .document!!
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.R) } }
            awaitTag(EditorTags.FIND_BAR_INPUT).performTextInput("x")
            awaitCondition { app.project.find.matches.size == 3 }
            awaitTag(EditorTags.REPLACE_INPUT).performTextInput("y")
            onNodeWithTag(EditorTags.REPLACE_ONE).performClick() // first click selects the current occurrence
            onNodeWithTag(EditorTags.REPLACE_ONE).performClick()
            awaitCondition { doc.lines[0] == "y1 x2 x3" }
            awaitCondition { doc.selectedText() == "x" && doc.selectionRange()!!.first == TextPos(0, 3) }
        }

    @Test
    fun `Go to Symbol finds declarations across the project`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.TREE).performClick()
            onNodeWithTag(TreeTags.TREE).performKeyInput {
                withKeyDown(
                    Key.CtrlLeft,
                ) { withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.N) } } }
            }
            awaitTag(TreeTags.GOTO_SYMBOL_INPUT).performTextInput("greetLoud")
            awaitTag(TreeTags.gotoSymbolItem("greetingIsLoud", "src/test/kotlin/AppTest.kt"))
            onNodeWithTag(TreeTags.GOTO_SYMBOL_INPUT).performKeyInput { pressKey(Key.Enter) }
            awaitTag(EditorTags.tab("src/test/kotlin/AppTest.kt"))
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret == TextPos(3, 8)
            }
        }

    @Test
    fun `Ctrl+B goes to a declaration in another file and Ctrl+Alt+Left comes back`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            val doc =
                app.project.editor.activeTab!!
                    .document!!
            onNodeWithTag(EditorTags.EDITOR).performClick()
            // Line 8: "    println(greeting.shout())", caret on "shout".
            runOnUiThread { doc.moveTo(TextPos(8, 23)) }
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
            awaitCondition {
                app.project.editor.activeTab
                    ?.entry
                    ?.name == "Strings.kt"
            }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.caret == TextPos(2, 11)
            }
            awaitEditorReady()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.DirectionLeft) } }
            }
            awaitCondition {
                app.project.editor.activeTab
                    ?.entry
                    ?.name == "App.kt"
            }
            assertEquals(TextPos(8, 23), doc.caret)
        }

    @Test
    fun `Ctrl+click on a local identifier jumps to its declaration`() =
        runComposeUiTest {
            project.file("Local.kt", textOf("fun helper() = 1", "", "fun use() = helper()", ""))
            val app = launchWithProject(project)
            openFromTree("Local.kt")
            val doc =
                app.project.editor.activeTab!!
                    .document!!
            awaitTag(EditorTags.line(2))
            // Locate column 14 ("helper") from the rendered text node: the font is monospace.
            val text =
                onNode(
                    hasText("fun use() = helper()", substring = false),
                    useUnmergedTree = true,
                ).fetchSemanticsNode().boundsInRoot
            val editor = onNodeWithTag(EditorTags.EDITOR).fetchSemanticsNode().boundsInRoot
            val charWidth = text.width / "fun use() = helper()".length
            val target = Offset(text.left - editor.left + 14.5f * charWidth, text.center.y - editor.top)
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { keyDown(Key.CtrlLeft) }
            onNodeWithTag(EditorTags.EDITOR).performMouseInput { click(target) }
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { keyUp(Key.CtrlLeft) }
            awaitCondition { doc.caret == TextPos(0, 4) }
        }
}
