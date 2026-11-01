package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.RemoteTags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val DIRECTORIES = 6_000
private const val SCROLL_STEPS = 60
private const val WHEEL_STEPS = 40
private const val WHEEL_DELTA = 100f
private const val MAX_COMPOSED_ROWS = 60

/**
 * Performance of the Open Project dialog on a directory with [DIRECTORIES] subdirectories: listing them, scrolling
 * the list and typing one more segment. The list is virtualized, so only the visible rows are composed; every
 * step has a budget that a slow CI runner passes but an algorithmic regression (a listing rebuilt per frame) fails.
 */
@OptIn(ExperimentalTestApi::class)
class OpenProjectDialogPerformanceE2eTest {
    private val parent = Files.createTempDirectory("open-project-perf")

    private fun directory(index: Int): Path = parent.resolve("branch-$index").createDirectories()

    @AfterTest
    fun cleanup() {
        parent.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.composedRows() =
        onAllNodes(hasTestTagPrefix("openProject.dir:")).fetchSemanticsNodes().size

    private fun ComposeUiTest.visibleRows(): List<String> =
        onAllNodes(hasTestTagPrefix("openProject.dir:"))
            .fetchSemanticsNodes()
            .mapNotNull {
                it.config
                    .getOrElseNullable(androidx.compose.ui.semantics.SemanticsProperties.TestTag) { null }
            }

    // A wheel scroll keeps the list moving frame after frame, unlike jumping to an index; measured net of the
    // same input plumbing and frames without movement, as the branch picker's benchmark does.
    private fun ComposeUiTest.measureWheel(list: SemanticsNodeInteraction) {
        list.performScrollToIndex(DIRECTORIES / 2)
        renderFrame()
        val before = visibleRows()
        repeat(5) {
            list.performMouseInput {
                moveTo(center)
                scroll(WHEEL_DELTA)
            }
            renderFrame()
        }
        assertTrue(visibleRows() != before, "the wheel must move the list")
        list.performScrollToIndex(DIRECTORIES / 2)
        renderFrame()
        val idle =
            costOf {
                repeat(WHEEL_STEPS) {
                    list.performMouseInput {
                        moveTo(center)
                        scroll(0f)
                    }
                    renderFrame()
                }
            }
        val wheel =
            costOf {
                repeat(WHEEL_STEPS) { step ->
                    // Alternate directions so the list stays in the middle, away from its ends.
                    list.performMouseInput {
                        moveTo(center)
                        scroll(if (step / 10 % 2 == 0) WHEEL_DELTA else -WHEEL_DELTA)
                    }
                    renderFrame()
                }
            }
        report(
            "scroll it with the wheel ($WHEEL_STEPS frames)",
            wheel - idle,
            budgetMillis = 12_000,
            budgetMegabytes = 300,
        )
    }

    @Test
    fun `a directory with thousands of subdirectories`() =
        runComposeUiTest {
            repeat(DIRECTORIES) { directory(it) }
            val app = launchApp()
            awaitCondition { app.state.serverHome != null }
            openProjectPickerFromWelcome()

            measure("list $DIRECTORIES directories", budgetMillis = 10_000, budgetMegabytes = 200) {
                showDirectoryInProjectPicker(parent.toString())
                awaitTag(RemoteTags.pickerDirectory(directory(0).toString()))
                renderFrame()
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed directory rows: ${composedRows()}")

            val list = onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(RemoteTags.OPEN_PROJECT)))
            measure(
                "scroll the directory list through $SCROLL_STEPS positions",
                budgetMillis = 15_000,
                budgetMegabytes = 200,
            ) {
                repeat(SCROLL_STEPS) { step ->
                    list.performScrollToIndex(1 + step * (DIRECTORIES / SCROLL_STEPS))
                    renderFrame()
                }
            }
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed directory rows after scrolling: ${composedRows()}")

            measureWheel(list)
            assertTrue(composedRows() < MAX_COMPOSED_ROWS, "composed directory rows after the wheel: ${composedRows()}")

            // Typing appends to the shown directory: one more segment selects a nested directory.
            val child = directory(2_999)
            measure("reach a directory by typing one segment", budgetMillis = 2_000, budgetMegabytes = 50) {
                val field = onNodeWithTag(RemoteTags.OPEN_PROJECT_PATH).requestFocus()
                field.performTextInput(parent.fileSystem.separator + child.fileName)
                field.performKeyInput { pressKey(Key.Enter) }
                awaitCondition { shownDirectoryInProjectPicker() == child.toString() }
                renderFrame()
            }
        }
}
