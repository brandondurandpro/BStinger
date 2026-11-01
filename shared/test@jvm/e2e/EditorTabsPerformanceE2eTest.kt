package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val TABS = 60
private const val WHEEL_STEPS = 40
private const val WHEEL_DELTA = 100f
private const val MAX_COMPOSED_TABS = 30

/**
 * Wheel scrolling of the editor's tab row on a project with [TABS] open tabs. The row is virtualized: only the tabs
 * on screen are composed, and a wheel step costs the same with 60 tabs as with 10.
 */
@OptIn(ExperimentalTestApi::class)
class EditorTabsPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.composedTabs(): List<String> =
        onAllNodes(hasTestTagPrefix("tab:"))
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrElseNullable(SemanticsProperties.TestTag) { null } }

    private fun ComposeUiTest.wheel(delta: Float) {
        onNodeWithTag(Tags.TABS).performMouseInput {
            moveTo(center)
            scroll(delta)
        }
        renderFrame()
    }

    @Test
    fun `scrolling a row of many tabs with the wheel`() =
        runComposeUiTest {
            repeat(TABS) { project.file("File$it.txt", "content $it\n") }
            val app = launchWithProject(project)
            runOnIdle {
                repeat(TABS) { index ->
                    val name = "File$index.txt"
                    app.project.openFile(FileEntry(project.root.resolve(name).toString(), name, isDirectory = false))
                }
            }
            awaitCondition { app.project.editor.tabs.size == TABS }
            awaitTag(Tags.tab("File${TABS - 1}.txt"))
            assertTrue(composedTabs().size < MAX_COMPOSED_TABS, "composed tabs: ${composedTabs().size}")

            // Warm up, and move away from the end of the row so that the measured steps alternate around the middle.
            val atEnd = composedTabs()
            repeat(5) { wheel(-WHEEL_DELTA) }
            assertTrue(composedTabs() != atEnd, "the wheel must move the row")
            val idle =
                costOf {
                    repeat(WHEEL_STEPS) { wheel(0f) }
                }
            val scrolling =
                costOf {
                    repeat(WHEEL_STEPS) { step ->
                        wheel(if (step / 10 % 2 == 0) WHEEL_DELTA else -WHEEL_DELTA)
                    }
                }
            report(
                "scroll $TABS editor tabs with the wheel ($WHEEL_STEPS frames)",
                scrolling - idle,
                budgetMillis = 12_000,
                budgetMegabytes = 300,
            )
            assertTrue(composedTabs().size < MAX_COMPOSED_TABS, "composed tabs after the wheel: ${composedTabs().size}")
        }
}
