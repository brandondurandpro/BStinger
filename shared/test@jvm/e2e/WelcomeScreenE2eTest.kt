package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.Screen
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@OptIn(ExperimentalTestApi::class)
class WelcomeScreenE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `first launch shows the welcome screen with an Open button`() =
        runComposeUiTest {
            val app = launchApp()
            onNodeWithTag(Tags.WELCOME).assertIsDisplayed()
            onNodeWithText("Welcome to Kotlin Editor").assertIsDisplayed()
            onNodeWithTag(Tags.WELCOME_OPEN).assertIsDisplayed()
            assertEquals("Welcome to Kotlin Editor", app.state.title)
        }

    @Test
    fun `Open asks for a directory and opens the project`() =
        runComposeUiTest {
            val app = launchApp(pick = project.path)
            onNodeWithTag(Tags.WELCOME_OPEN).performClick()
            awaitTag(Tags.PROJECT)
            assertEquals(1, app.picker.calls)
            awaitTag(Tags.treeRow(project.root.fileName.toString())).assertIsDisplayed()
            assertEquals(project.root.fileName.toString(), app.state.title)
            // The project is remembered as the most recent one.
            awaitCondition { app.recentPaths == listOf(project.path) }
        }

    @Test
    fun `cancelling the directory chooser stays on the welcome screen`() =
        runComposeUiTest {
            val app = launchApp(pick = null)
            onNodeWithTag(Tags.WELCOME_OPEN).performClick()
            waitForIdle()
            assertEquals(1, app.picker.calls)
            assertIs<Screen.Welcome>(app.state.screen)
        }

    @Test
    fun `recent projects are listed and open on click`() =
        runComposeUiTest {
            val other = SampleProject().apply { file("Other.kt", "class Other") }
            try {
                launchApp(recent = listOf(project.path, other.path))
                onNodeWithTag(ItemTags.recentProject(project.path)).assertIsDisplayed()
                onNodeWithTag(ItemTags.recentProject(other.path)).assertIsDisplayed()
                onNodeWithTag(ItemTags.recentProject(other.path)).performClick()
                awaitTag(Tags.treeRow("Other.kt")).assertIsDisplayed()
            } finally {
                other.delete()
            }
        }

    @Test
    fun `searching filters recent projects`() =
        runComposeUiTest {
            launchApp(recent = listOf(project.path, "/does/not/exist/zeta"))
            onNodeWithTag(Tags.WELCOME_SEARCH).performTextInput("zeta")
            awaitNoTag(ItemTags.recentProject(project.path))
            onNodeWithTag(ItemTags.recentProject("/does/not/exist/zeta")).assertIsDisplayed()
        }

    @Test
    fun `a recent project can be removed from the list`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, "/tmp/elsewhere"))
            onNodeWithTag(ItemTags.recentProject(project.path)).performMouseInput { enter(center) }
            awaitTag(ItemTags.recentRemove(project.path)).performClick()
            awaitNoTag(ItemTags.recentProject(project.path))
            awaitCondition { app.recentPaths == listOf("/tmp/elsewhere") }
        }

    @Test
    fun `opening a recent project that no longer exists shows an error`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf("/definitely/missing/project"))
            onNodeWithTag(ItemTags.recentProject("/definitely/missing/project")).performClick()
            awaitTag(Tags.WELCOME_ERROR).assertIsDisplayed()
            assertIs<Screen.Welcome>(app.state.screen)
        }

    @Test
    fun `closing the project returns to the welcome screen with the project in recents`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.PROJECT_WIDGET).performClick()
            awaitTag(Tags.PROJECT_CLOSE).performClick()
            awaitTag(Tags.WELCOME)
            assertIs<Screen.Welcome>(app.state.screen)
            onNodeWithTag(ItemTags.recentProject(project.path)).assertIsDisplayed()
        }
}
