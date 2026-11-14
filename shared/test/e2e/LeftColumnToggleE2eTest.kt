package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test

/** The toolbar button that hides the left column of the IDE (Project, Git) and brings it back. */
@OptIn(ExperimentalTestApi::class)
class LeftColumnToggleE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the toolbar button hides the left column then shows it again`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(TreeTags.PROJECT_PANEL)
            awaitTag(AppTags.TOOLBAR_LEFT_COLUMN).performClick()
            awaitNoTag(TreeTags.PROJECT_PANEL)
            awaitTag(AppTags.TOOLBAR_LEFT_COLUMN).performClick()
            awaitTag(TreeTags.PROJECT_PANEL)
        }
}
