package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.EditorRenderer
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The canvas renderer (default) and the composed rows it replaces, switched with Ctrl+Alt+Shift+R. */
@OptIn(ExperimentalTestApi::class)
class EditorRenderersE2eTest {
    private val project =
        SampleProject.standard().apply {
            file("Long.kt", (1..2000).joinToString("\n") { "val line$it = $it" })
            file("Fold.kt", textOf("class Fold {", "    fun a() {", """        println("a")""", "    }", "}", ""))
        }
    private val dir = Files.createTempDirectory("kotlin-editor-renderer").toFile()
    private val file = dir.resolve("preferences.properties")

    @AfterTest
    fun cleanup() {
        project.delete()
        dir.deleteRecursively()
    }

    private fun androidx.compose.ui.test.ComposeUiTest.switchRenderer() {
        onNodeWithTag(Tags.EDITOR).performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.AltLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.R) } }
            }
        }
        waitForIdle()
    }

    @Test
    fun `Ctrl+Alt+Shift+R switches the renderer and the choice is restored`() {
        runComposeUiTest {
            val app = launchWithProject(project, FilePreferenceStore(file))
            assertEquals(EditorRenderer.Canvas, app.state.editorRenderer)
            openFromTree("README.md")
            switchRenderer()
            assertEquals(EditorRenderer.Rows, app.state.editorRenderer)
            awaitTag(Tags.line(0)).assertTextContains("# Demo")
            awaitCondition { FilePreferenceStore(file).load()[PreferenceKeys.EDITOR_RENDERER] == "Rows" }
        }

        runComposeUiTest {
            val app = launchWithProject(project, FilePreferenceStore(file))
            assertEquals(EditorRenderer.Rows, app.state.editorRenderer)
            openFromTree("README.md")
            switchRenderer()
            assertEquals(EditorRenderer.Canvas, app.state.editorRenderer)
            awaitTag(Tags.line(0)).assertTextContains("# Demo")
        }
    }

    @Test
    fun `the composed rows still type, scroll and fold`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            runOnUiThread { app.state.toggleEditorRenderer() }
            openFromTree("Fold.kt")
            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput {
                withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) }
                pressKey(Key.X)
            }
            awaitTag(Tags.line(0)).assertTextContains("xclass Fold {")
            awaitTag(Tags.foldMarker(1), unmerged = true).performClick()
            awaitNoTag(Tags.line(2))
            openFromTree("Long.kt")
            onNode(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag(Tags.EDITOR))).performScrollToIndex(1500)
            awaitTag(Tags.line(1500)).assertTextContains("val line1501 = 1501")
            val tab = app.project.editor.activeTab!!
            assertTrue(tab.listState.firstVisibleItemIndex in 1400..1500)
        }

    @Test
    fun `the mouse wheel scrolls the canvas`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("Long.kt")
            awaitTag(Tags.line(0))
            onNodeWithTag(Tags.EDITOR).performMouseInput {
                moveTo(center)
                scroll(20f)
            }
            val tab = app.project.editor.activeTab!!
            val scroll = tab.verticalScroll
            awaitCondition { scroll.firstRow > 0 }
            mainClock.advanceTimeBy(WHEEL_ANIMATION_MILLIS)
            waitForIdle()
            awaitTag(Tags.line(scroll.firstRow))
            awaitNoTag(Tags.line(0))
        }

    private companion object {
        const val WHEEL_ANIMATION_MILLIS = 1000L
    }
}
