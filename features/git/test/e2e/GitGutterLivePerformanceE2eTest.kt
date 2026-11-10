package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

private const val LINES = 20_000

/** The gutter marks of a file of 20,000 lines, computed again as it is edited. */
@OptIn(ExperimentalTestApi::class)
class GitGutterLivePerformanceE2eTest {
    private val project = SampleProject.standard()

    private fun E2eApp.tab() = project.editor.activeTab

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a line typed in a file of 20,000 lines is marked quickly`() =
        runComposeUiTest {
            project.file("big.txt", (1..LINES).joinToString("\n", postfix = "\n") { "line $it" })
            project.commitAll()
            val app = launchWithProject(project)
            openFromTree("big.txt")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            awaitCondition { app.tab()?.followsHead == true }
            caretToEnd()
            measure("mark of a line typed in $LINES lines", budgetMillis = 20_000, budgetMegabytes = 800) {
                typeCode("last")
                awaitTag(EditorTags.changeMarker(LINES, "Added"), unmerged = true)
                renderFrame()
            }
        }
}
