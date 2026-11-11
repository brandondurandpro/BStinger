package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

@OptIn(ExperimentalTestApi::class)
class FindBarFocusE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `Ctrl+F focuses the search field so the user can type without a click`() =
        runComposeUiTest {
            project.file("Find.kt", "val needle = 1\nval needle2 = 2\n")
            launchWithProject(project)
            openFromTree("Find.kt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitCondition { runCatching { onNodeWithTag(EditorTags.FIND_BAR_INPUT).assertIsFocused() }.isSuccess }
        }

    @Test
    fun `Ctrl+F while the bar is open refocuses and selects its text, Escape returns to the editor`() =
        runComposeUiTest {
            project.file("Find.kt", "val needle = 1\nval needle2 = 2\n")
            val app = launchWithProject(project)
            openFromTree("Find.kt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitTag(EditorTags.FIND_BAR_INPUT).performTextInput("needle")
            awaitCondition { app.project.find.query == "needle" }

            onNodeWithTag(EditorTags.EDITOR).performClick() // focus leaves the field
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } }
            awaitCondition { runCatching { onNodeWithTag(EditorTags.FIND_BAR_INPUT).assertIsFocused() }.isSuccess }
            awaitCondition { selectionOfFindField() == TextRange(0, "needle".length) }

            onNodeWithTag(EditorTags.FIND_BAR_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.FIND_BAR)
            awaitCondition { runCatching { onNodeWithTag(EditorTags.EDITOR).assertIsFocused() }.isSuccess }
        }

    private fun ComposeUiTest.selectionOfFindField(): TextRange? {
        val config = onNodeWithTag(EditorTags.FIND_BAR_INPUT).fetchSemanticsNode().config
        return config.getOrNull(SemanticsProperties.TextSelectionRange)
    }
}
