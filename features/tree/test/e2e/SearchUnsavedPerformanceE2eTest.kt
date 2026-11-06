package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.SearchOptions
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.UnsavedDocumentsSearch
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.unsavedDocuments
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LINES = 10_000
private const val QUERY = "text to fill"
private const val MARKER = "zzunsavedzz"

/** The user-visible cost of Find in Files over a document modified in memory: its search, and its merged display. */
@OptIn(ExperimentalTestApi::class)
class SearchUnsavedPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openFindInFiles() {
        onNodeWithTag(TreeTags.TREE).performClick()
        onNodeWithTag(TreeTags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        awaitTag(TreeTags.FIND_IN_FILES_INPUT)
    }

    @Test
    fun `a modified document of ten thousand lines searches and merges with the disk results`() =
        runComposeUiTest {
            val lines = (1..LINES).joinToString("\n") { index -> "line $index $QUERY in every line" }
            project.file("huge/big.txt", lines)
            val app = launchWithProject(project)
            openFromTree("huge/big.txt")
            val document = app.project.editor.activeTab!!.document!!
            // One edit marks the buffer modified: an extra first line the disk does not have yet.
            runOnUiThread { document.replace(TextPos(0, 0), TextPos(0, 0), "$MARKER\n") }
            waitForIdle()

            // The in-memory search itself: every line of the buffer matches, the hits are built and sorted.
            measure("search a modified document of $LINES lines", budgetMillis = 2_000, budgetMegabytes = 400) {
                val documents = app.project.editor.unsavedDocuments()
                val hits =
                    UnsavedDocumentsSearch(app.project.fileIndex::relativePath).hits(
                        QUERY,
                        documents,
                        SearchOptions(ignoreCase = false),
                    )
                assertEquals(LINES, hits.size)
            }

            // The user's step: the query typed in the popup, over a matching file on disk and the modified
            // buffer (its hits replace the disk ones of the same file), listing thousands of results.
            openFindInFiles()
            measure("list the $LINES matches of a document being edited", budgetMillis = 15_000, budgetMegabytes = 800) {
                onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput(QUERY)
                val count = onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT)
                awaitCondition { runCatching { count.assertTextEquals("$LINES matches in 1 file") }.isSuccess }
                renderFrame()
            }
            // The list is virtualized: displaying thousands of hits composes a bounded number of rows.
            val rows = onAllNodes(hasTestTagPrefix("findInFiles.item")).fetchSemanticsNodes().size
            assertTrue(rows < 200, "composed rows: $rows")
            onNodeWithTag(TreeTags.findInFilesItem("huge/big.txt", 1)).assertIsDisplayed()
        }
}
