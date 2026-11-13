package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val CENTER_TOLERANCE = 1f

/** The toolbar of a project: the project at the left, the search in the middle, what runs and ships at the right. */
@OptIn(ExperimentalTestApi::class)
class ToolbarLayoutE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the gear at the bottom left opens the settings`() =
        runComposeUiTest {
            launchWithProject(project)
            val frame = awaitTag(AppTags.PROJECT).getBoundsInRoot()
            val gear = awaitTag(AppTags.STRIP_SETTINGS).getBoundsInRoot()
            assertTrue(gear.left.value < (frame.right.value - frame.left.value) / 4, "the gear is not at the left")
            assertTrue(gear.top.value > (frame.bottom.value - frame.top.value) / 2, "the gear is not at the bottom")
            awaitTag(AppTags.STRIP_SETTINGS).performClick()
            awaitTag(AppTags.SETTINGS)
            awaitTag(AppTags.SETTINGS_CLOSE).performClick()
            awaitNoTag(AppTags.SETTINGS)
        }

    @Test
    fun `the search sits in the middle of the toolbar, between the project and the Deploy button`() =
        runComposeUiTest {
            launchWithProject(project)
            val frame = awaitTag(AppTags.PROJECT).getBoundsInRoot()
            val search = awaitTag(AppTags.TOOLBAR_SEARCH).getBoundsInRoot()
            val widget = awaitTag(AppTags.PROJECT_WIDGET).getBoundsInRoot()
            val deploy = awaitTag(AppTags.DEPLOY_BUTTON).getBoundsInRoot()
            val searchCenter = (search.left.value + search.right.value) / 2
            val frameCenter = (frame.left.value + frame.right.value) / 2
            assertTrue(kotlin.math.abs(searchCenter - frameCenter) <= CENTER_TOLERANCE, "search is not centered")
            assertTrue(widget.right.value <= search.left.value, "the project is not left of the search")
            assertTrue(search.right.value <= deploy.left.value, "Deploy is not right of the search")
        }
}
