package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.VerticalScroll
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val FILES = 10_000
private const val SCROLL_STEPS = 200
private const val STEPS = 300
private const val ROWS_PER_STEP = 3
private const val ROUNDS = 3

// Rows before the files of "huge": the root, .git, docs and huge itself.
private const val ROWS_BEFORE = 4
private const val KEY_PRESSES = 200
private const val MAX_COMPOSED_ROWS = 100

/**
 * Performance of the project tree on a directory of [FILES] files, through the real UI and server: listing and
 * expanding it, scrolling it with the sticky directory names, moving the selection with the keyboard, collapsing and
 * expanding it again. Each step prints a `PERF` line and has a budget. The scrolls are measured like the editor's
 * ([EditorScrollBenchmark]): each step rendered, the cost of rendering unchanged frames subtracted, median of
 * [ROUNDS], jumping from row to row and as during a wheel or drag scroll. When the tree moved from composed rows to
 * one canvas, these went from 32 MB / 1033 ms (jumps) and 31 MB / 789 ms (user scroll) to 22 MB / 622 ms and
 * 7 MB / 406 ms.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectTreePerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun name(i: Int) = "huge/file${i.toString().padStart(5, '0')}.txt"

    private fun ComposeUiTest.composedRows() = onAllNodes(hasTestTagPrefix("tree:")).fetchSemanticsNodes().size

    // Net cost of STEPS rendered steps of ROWS_PER_STEP rows from [from]; [userScroll] keeps a scroll in progress.
    private fun ComposeUiTest.scrollCost(scroll: VerticalScroll, from: Int, userScroll: Boolean): Cost {
        fun pass(moving: Boolean): Cost {
            runBlocking { scroll.scrollToRow(from) }
            waitForIdle()
            val held = CoroutineScope(Dispatchers.Unconfined)
            if (userScroll) held.launch { scroll.scrollableState.scroll { awaitCancellation() } }
            val cost =
                costOf {
                    repeat(STEPS) { step ->
                        if (moving) runBlocking { scroll.scrollToRow(from + step * ROWS_PER_STEP) }
                        renderFrame()
                    }
                }
            held.cancel()
            return cost
        }
        pass(moving = true) // warm-up
        val rounds = List(ROUNDS) { pass(moving = true) - pass(moving = false) }
        return Cost(rounds.map { it.nanos }.sorted()[ROUNDS / 2], rounds.map { it.bytes }.sorted()[ROUNDS / 2])
    }

    @Test
    fun `a directory of ten thousand files`() =
        runComposeUiTest {
            repeat(FILES) { project.file(name(it), "") }
            val app = launchWithProject(project)
            val tree = app.project.tree
            val list = onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(Tags.TREE)))

            measure("expand a directory of $FILES files", budgetMillis = 5_000) {
                onNodeWithTag(Tags.treeChevron("huge")).performClick()
                awaitTag(Tags.treeRow(name(0)))
                renderFrame()
            }
            assertTrue(tree.rows.size > FILES)
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed rows: ${composedRows()}")

            val scroll = tree.scroll
            val rows = "$STEPS steps of $ROWS_PER_STEP rows"
            report("scroll it, jumping ($rows)", scrollCost(scroll, FILES / 2, userScroll = false), 6_000, 100)
            awaitTag(Tags.treeSticky("huge"))
            report("scroll it, as a user ($rows)", scrollCost(scroll, FILES / 2, userScroll = true), 4_000, 40)
            measure("scroll it top to bottom in $SCROLL_STEPS frames", budgetMillis = 15_000, budgetMegabytes = 400) {
                repeat(SCROLL_STEPS) { step ->
                    list.performScrollToIndex(1 + step * (FILES / SCROLL_STEPS))
                    renderFrame()
                }
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed rows after scrolling: ${composedRows()}")

            // A row in view below the sticky headers.
            val first = FILES - FILES / SCROLL_STEPS
            runBlocking { scroll.scrollToRow(first) }
            val start = first + 2 * ROWS_PER_STEP - ROWS_BEFORE
            awaitTag(Tags.treeRow(name(start))).performClick()
            measure("move the selection $KEY_PRESSES rows up", budgetMillis = 10_000) {
                repeat(KEY_PRESSES) {
                    onNodeWithTag(Tags.TREE).performKeyInput { pressKey(Key.DirectionUp) }
                    renderFrame()
                }
            }
            awaitCondition { tree.selectedPath == project.root.resolve(name(start - KEY_PRESSES)).toString() }

            list.performScrollToIndex(0)
            renderFrame()
            measure("collapse it", budgetMillis = 2_000) {
                onNodeWithTag(Tags.treeChevron("huge")).performClick()
                awaitNoTag(Tags.treeRow(name(0)))
                renderFrame()
            }
            measure("expand it again (already listed)", budgetMillis = 2_000) {
                onNodeWithTag(Tags.treeChevron("huge")).performClick()
                awaitTag(Tags.treeRow(name(0)))
                renderFrame()
            }
        }
}
