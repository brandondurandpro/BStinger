package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NewWindowE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `Ctrl+Alt+Shift+W asks the desktop app for another window`() =
        runComposeUiTest {
            project.file("Window.kt", "val a = 1\n")
            val app = launchWithProject(project)
            var requested = 0
            app.state.onNewWindow = { requested++ }
            openFromTree("Window.kt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                keyDown(Key.CtrlLeft)
                keyDown(Key.AltLeft)
                keyDown(Key.ShiftLeft)
                pressKey(Key.W)
                keyUp(Key.ShiftLeft)
                keyUp(Key.AltLeft)
                keyUp(Key.CtrlLeft)
            }
            awaitCondition { requested == 1 }
            assertEquals(1, requested)
        }
}
