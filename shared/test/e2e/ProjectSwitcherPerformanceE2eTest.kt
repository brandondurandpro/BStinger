package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// The most recent projects the IDE remembers (AppState.MAX_RECENT).
private const val RECENT = 20
private const val FILES = 10_000

/**
 * Performance of the project menu of the toolbar: opening it with the most recent projects the IDE remembers, then
 * switching to a project of [FILES] files until its tree is shown. Each step prints a `PERF` line and has a budget.
 */
@OptIn(ExperimentalTestApi::class)
class ProjectSwitcherPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val huge =
        SampleProject().apply {
            repeat(FILES) { i -> file("file${i.toString().padStart(5, '0')}.txt", "line $i\n") }
        }

    @AfterTest
    fun cleanup() {
        project.delete()
        huge.delete()
    }

    @Test
    fun `opening the menu with many recent projects and switching to a huge one stays fast`() =
        runComposeUiTest {
            val missing = List(RECENT - 2) { "/does/not/exist/project$it" }
            launchApp(recent = listOf(project.path, huge.path) + missing)
            openRecentFromWelcome(project.path)
            measure("open the project menu ($RECENT recent projects)", budgetMillis = 2_000, budgetMegabytes = 200) {
                onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
                awaitTag(AppTags.switchProject(huge.path))
                renderFrame()
            }
            assertEquals(RECENT - 1, onAllNodes(hasTestTagPrefix("project.switch:")).fetchSemanticsNodes().size)
            measure("switch to a project of $FILES files", budgetMillis = 10_000, budgetMegabytes = 1_000) {
                onNodeWithTag(AppTags.switchProject(huge.path)).performClick()
                awaitTag(TreeTags.treeRow("file00000.txt"))
                renderFrame()
            }
        }
}
