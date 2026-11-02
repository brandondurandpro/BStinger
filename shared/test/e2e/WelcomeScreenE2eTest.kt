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
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.RemoteTags
import org.example.kotlineditor.ui.TreeTags
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
            onNodeWithTag(AppTags.WELCOME).assertIsDisplayed()
            onNodeWithText("Welcome to Kotlin Editor").assertIsDisplayed()
            onNodeWithTag(AppTags.WELCOME_OPEN).assertIsDisplayed()
            assertEquals("Welcome to Kotlin Editor", app.state.title)
        }

    @Test
    fun `Open browses to a directory and opens it as a project`() =
        runComposeUiTest {
            val app = launchApp()
            openProjectDirectoryFromWelcome(project.path)
            awaitTag(AppTags.PROJECT)
            awaitTag(TreeTags.treeRow(project.root.fileName.toString())).assertIsDisplayed()
            assertEquals(project.root.fileName.toString(), app.state.title)
            // The project is remembered as the most recent one.
            awaitCondition { app.recentPaths == listOf(project.path) }
        }

    @Test
    fun `cancelling the Open Project dialog stays on the welcome screen`() =
        runComposeUiTest {
            val app = launchApp()
            openProjectPickerFromWelcome()
            onNodeWithTag(RemoteTags.OPEN_PROJECT_CANCEL).performClick()
            awaitNoTag(RemoteTags.OPEN_PROJECT)
            assertIs<Screen.Welcome>(app.state.screen)
        }

    @Test
    fun `recent projects are listed and open on click`() =
        runComposeUiTest {
            val other = SampleProject().apply { file("Other.kt", "class Other") }
            try {
                launchApp(recent = listOf(project.path, other.path))
                onNodeWithTag(AppTags.recentProject(project.path)).assertIsDisplayed()
                onNodeWithTag(AppTags.recentProject(other.path)).assertIsDisplayed()
                onNodeWithTag(AppTags.recentProject(other.path)).performClick()
                awaitTag(TreeTags.treeRow("Other.kt")).assertIsDisplayed()
            } finally {
                other.delete()
            }
        }

    @Test
    fun `searching filters recent projects`() =
        runComposeUiTest {
            launchApp(recent = listOf(project.path, "/does/not/exist/zeta"))
            onNodeWithTag(AppTags.WELCOME_SEARCH).performTextInput("zeta")
            awaitNoTag(AppTags.recentProject(project.path))
            onNodeWithTag(AppTags.recentProject("/does/not/exist/zeta")).assertIsDisplayed()
        }

    @Test
    fun `a recent project can be removed from the list`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, "/tmp/elsewhere"))
            onNodeWithTag(AppTags.recentProject(project.path)).performMouseInput { enter(center) }
            awaitTag(AppTags.recentRemove(project.path)).performClick()
            awaitNoTag(AppTags.recentProject(project.path))
            awaitCondition { app.recentPaths == listOf("/tmp/elsewhere") }
        }

    @Test
    fun `opening a recent project that no longer exists shows an error`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf("/definitely/missing/project"))
            onNodeWithTag(AppTags.recentProject("/definitely/missing/project")).performClick()
            awaitTag(AppTags.WELCOME_ERROR).assertIsDisplayed()
            assertIs<Screen.Welcome>(app.state.screen)
        }

    @Test
    fun `closing the project returns to the welcome screen with the project in recents`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.PROJECT_CLOSE).performClick()
            awaitTag(AppTags.WELCOME)
            assertIs<Screen.Welcome>(app.state.screen)
            onNodeWithTag(AppTags.recentProject(project.path)).assertIsDisplayed()
        }
}
