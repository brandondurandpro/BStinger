package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.MergeChoice
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FILE = "huge.txt"
private const val LINES = 20_000
private const val CONFLICT_EVERY = 1_000
private const val MAX_COMPOSED_ROWS = 150

/**
 * Performance of the merge of a file of [LINES] lines with a conflict every [CONFLICT_EVERY] lines, and a change of
 * each side in between: opening it, deciding the conflicts, scrolling to the next one and applying. The rows are
 * virtualized, so the ones composed stay far below the lines listed.
 */
@OptIn(ExperimentalTestApi::class)
class MergePerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun lines(change: (Int) -> String?): String =
        (0 until LINES).joinToString("\n", postfix = "\n") { change(it) ?: "line $it" }

    private fun ComposeUiTest.composedRows() = onAllNodes(hasTestTagPrefix("merge.row:")).fetchSemanticsNodes().size

    @Test
    fun `a merge of twenty thousand lines opens, decides and applies in time`() =
        runComposeUiTest {
            val base = lines { null }
            val ours =
                lines {
                    if (it % CONFLICT_EVERY ==
                        10
                    ) {
                        "ours $it"
                    } else if (it % CONFLICT_EVERY == 300) {
                        "ours only $it"
                    } else {
                        null
                    }
                }
            val theirs =
                lines {
                    if (it % CONFLICT_EVERY ==
                        10
                    ) {
                        "theirs $it"
                    } else if (it % CONFLICT_EVERY == 600) {
                        "theirs only $it"
                    } else {
                        null
                    }
                }
            project.startConflictingMerge(FILE, base, ours, theirs)
            val app = launchWithProject(project)
            val conflicts = LINES / CONFLICT_EVERY

            lateinit var merge: org.example.kotlineditor.state.MergeState
            measure("open the merge of $LINES lines", budgetMillis = 15_000, budgetMegabytes = 400) {
                merge = openMergeFromGitPanel(app, FILE)
                renderFrame()
            }
            assertEquals(conflicts, merge.unresolved)
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed rows: ${composedRows()}")

            measure("jump to the next of $conflicts conflicts", budgetMillis = 3_000, budgetMegabytes = 100) {
                onNodeWithTag(EditorTags.MERGE_NEXT).performClick()
                renderFrame()
            }
            val first = merge.firstConflict()
            measure("decide one conflict", budgetMillis = 3_000, budgetMegabytes = 100) {
                awaitTag(EditorTags.mergeChoice(first, MergeChoice.Theirs.name)).performClick()
                awaitCondition { merge.unresolved == conflicts - 1 }
                renderFrame()
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed rows: ${composedRows()}")

            measure("accept all ours, apply and write $LINES lines", budgetMillis = 15_000, budgetMegabytes = 400) {
                onNodeWithTag(EditorTags.MERGE_ALL_OURS).performClick()
                awaitCondition { merge.unresolved == 0 }
                onNodeWithTag(EditorTags.MERGE_APPLY).performClick()
                awaitNoTag(EditorTags.MERGE, timeout = 30_000)
            }
            assertEquals(emptyList(), project.unmergedPaths())
            assertTrue("theirs only 600" in project.read(FILE))
        }
}
