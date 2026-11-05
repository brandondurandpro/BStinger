package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val LINES = 200_000
private const val EDIT_BUDGET_MS = 3_000L

/**
 * Edits that replace most of a 200,000-line file at once, through the real UI. The lines removed by one edit used to go
 * one at a time, each moving every line after it: typing over the whole file selected froze the IDE for 15 seconds.
 */
@OptIn(ExperimentalTestApi::class)
class EditingPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `typing over the whole of a large file selected, then undoing it`() =
        runComposeUiTest {
            project.file("large.txt", (1..LINES).joinToString("\n") { "line $it of a large file" })
            val app = launchWithProject(project)
            openFromTree("large.txt")
            val tab = checkNotNull(app.project.editor.activeTab)
            val document = checkNotNull(tab.document)
            onNodeWithTag(EditorTags.EDITOR).performClick()
            pressInEditor(Key.A, ctrl = true)
            measure("type a key over the $LINES lines selected", EDIT_BUDGET_MS) {
                pressInEditor(Key.W)
                renderFrame()
            }
            assertEquals(listOf("w"), document.lines.toList())
            measure("undo it, bringing the $LINES lines back", EDIT_BUDGET_MS) {
                pressInEditor(Key.Z, ctrl = true)
                renderFrame()
            }
            assertEquals(LINES, document.lineCount)
            assertEquals("line $LINES of a large file", document.lines.lastOrNull())
        }
}
