package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class FoldingE2eTest {
    private val project =
        SampleProject.standard().apply {
            file(
                "Fold.kt",
                """
                class Fold {
                    fun a() {
                        println("a")
                    }

                    fun b() {
                        println("b")
                    }
                }
                """.trimIndent(),
            )
        }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openFold(app: E2eApp): Document {
        openFromTree("Fold.kt")
        val doc =
            app.project.editor.activeTab!!
                .document!!
        awaitCondition { doc.folding.regions.isNotEmpty() }
        return doc
    }

    @Test
    fun `clicking a fold marker collapses and expands a block`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFold(app)
            awaitTag(Tags.foldMarker(1), unmerged = true).performClick()
            awaitNoTag(Tags.line(2))
            awaitNoTag(Tags.line(3))
            onNodeWithTag(Tags.line(1)).assertTextContains("...}", substring = true)
            onNodeWithTag(Tags.line(4)).assertExists()
            onNodeWithTag(Tags.foldMarker(1), useUnmergedTree = true).performClick()
            awaitTag(Tags.line(2)).assertTextContains("println(\"a\")", substring = true)
        }

    @Test
    fun `Ctrl+minus folds the block at the caret and Ctrl+equals unfolds it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val doc = openFold(app)
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.moveTo(TextPos(6, 4)) }
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Minus) } }
            awaitNoTag(Tags.line(6))
            assertEquals(setOf(5), doc.folding.folded)
            assertEquals(5, doc.caret.line) // the caret left the hidden lines
            onNodeWithTag(Tags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Equals) } }
            awaitTag(Tags.line(6))
        }

    @Test
    fun `fold all then arrow keys skip folded lines`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val doc = openFold(app)
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.moveTo(TextPos(1, 0)) }
            runOnUiThread { doc.folding.regions = doc.folding.regions - 0 } // keep the class body open
            onNodeWithTag(
                Tags.EDITOR,
            ).performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Minus) } } }
            awaitNoTag(Tags.line(2))
            onNodeWithTag(Tags.EDITOR).performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            assertEquals(4, doc.caret.line)
            onNodeWithTag(Tags.EDITOR).performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            assertEquals(5, doc.caret.line)
        }

    @Test
    fun `going to a hidden line unfolds its region`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val doc = openFold(app)
            awaitTag(Tags.foldMarker(1), unmerged = true).performClick()
            awaitNoTag(Tags.line(2))
            runOnUiThread { doc.moveTo(TextPos(2, 0)) }
            awaitTag(Tags.line(2))
            assertTrue(doc.folding.folded.isEmpty())
        }

    @Test
    fun `adding a line drops the folds instead of hiding the wrong code`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val doc = openFold(app)
            awaitTag(Tags.foldMarker(5), unmerged = true).performClick()
            awaitNoTag(Tags.line(6))
            onNodeWithTag(Tags.EDITOR).performClick()
            runOnUiThread { doc.moveTo(TextPos(0, 0)) }
            onNodeWithTag(Tags.EDITOR).performKeyInput { pressKey(Key.Enter) }
            awaitTag(Tags.line(7))
            assertTrue(doc.folding.folded.isEmpty())
        }
}
