package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The menu of the project name in the toolbar, which lists the other recent projects and switches to them. */
@OptIn(ExperimentalTestApi::class)
class ProjectSwitcherE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject().apply { file("Other.kt", "class Other") }

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
    }

    @Test
    fun `the project menu lists the other recent projects but not the open one`() =
        runComposeUiTest {
            launchApp(recent = listOf(project.path, other.path))
            openRecentFromWelcome(project.path)
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.switchProject(other.path)).assertIsDisplayed()
            onNodeWithText("Recent Projects").assertIsDisplayed()
            awaitNoTag(AppTags.switchProject(project.path))
            onNodeWithTag(AppTags.PROJECT_OPEN_OTHER).assertIsDisplayed()
            onNodeWithTag(AppTags.PROJECT_CLOSE).assertIsDisplayed()
        }

    @Test
    fun `Open Project and Go Homepage come before the recent projects`() =
        runComposeUiTest {
            launchApp(recent = listOf(project.path, other.path))
            openRecentFromWelcome(project.path)
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            val open = awaitTag(AppTags.PROJECT_OPEN_OTHER).assertTextEquals("Open Project…").getBoundsInRoot()
            val home = onNodeWithTag(AppTags.PROJECT_CLOSE).assertTextEquals("Go Homepage").getBoundsInRoot()
            val recent = onNodeWithTag(AppTags.switchProject(other.path)).getBoundsInRoot()
            assertTrue(open.top < home.top && home.top < recent.top, "open $open, home $home, recent $recent")
        }

    @Test
    fun `the menu is wide enough to show a long project path in full`() =
        runComposeUiTest {
            val base = Files.createTempDirectory("kotlin-editor-e2e")
            val nested =
                "a-rather-long-directory-name/with-another-long-level/and-yet-another-one/" +
                    "another-level-here/and-one-more-level/deep-project"
            val deep = SampleProject(base.resolve(nested).createDirectories()).apply { file("Deep.kt", "class Deep") }
            try {
                launchApp(recent = listOf(project.path, deep.path))
                openRecentFromWelcome(project.path)
                onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
                awaitTag(AppTags.switchProject(deep.path))
                val label =
                    onNode(hasText(deep.path) and hasAnyAncestor(hasTestTag(AppTags.switchProject(deep.path))), true)
                        .fetchSemanticsNode()
                val layouts = mutableListOf<TextLayoutResult>()
                label.config[SemanticsActions.GetTextLayoutResult].action!!(layouts)
                assertFalse(layouts.single().hasVisualOverflow, "The path ${deep.path} is cut")
            } finally {
                base.toFile().deleteRecursively()
            }
        }

    @Test
    fun `clicking a recent project in the menu switches to it`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path))
            openRecentFromWelcome(project.path)
            switchProjectTo(other.path)
            awaitTag(TreeTags.treeRow("Other.kt")).assertIsDisplayed()
            assertEquals(other.root.fileName.toString(), app.state.title)
            // It becomes the most recent project, and the one left is now offered in the menu.
            awaitCondition { app.recentPaths == listOf(other.path, project.path) }
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.switchProject(project.path))
            awaitNoTag(AppTags.switchProject(other.path))
        }

    @Test
    fun `switching back reopens the files of the project left`() =
        runComposeUiTest {
            launchApp(recent = listOf(project.path, other.path))
            openRecentFromWelcome(project.path)
            openFromTree("README.md")
            switchProjectTo(other.path)
            awaitTag(TreeTags.treeRow("Other.kt"))
            awaitNoTag(EditorTags.tab("README.md"))
            switchProjectTo(project.path)
            awaitTag(EditorTags.tab("README.md"))
        }

    @Test
    fun `with no other recent project the menu only offers Open Project and Go Homepage`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.PROJECT_OPEN_OTHER)
            assertTrue(onAllNodes(hasTestTagPrefix("project.switch:")).fetchSemanticsNodes().isEmpty())
            assertTrue(onAllNodes(hasText("Recent Projects")).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `Escape closes the menu without leaving the project`() =
        runComposeUiTest {
            val app = launchApp(recent = listOf(project.path, other.path))
            openRecentFromWelcome(project.path)
            val opened = app.project
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.PROJECT_OPEN_OTHER).requestFocus().performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AppTags.PROJECT_MENU)
            waitForIdle()
            assertTrue(app.project === opened)
        }

    @Test
    fun `switching to a project that no longer exists says so and keeps the open project`() =
        runComposeUiTest {
            val missing = "/definitely/missing/project"
            val app = launchApp(recent = listOf(project.path, missing, other.path))
            openRecentFromWelcome(project.path)
            val opened = app.project
            switchProjectTo(missing)
            awaitTag(AppTags.STATUS_ERROR).assertTextContains("Directory not found", substring = true)
            assertTrue(app.project === opened)
            awaitTag(TreeTags.treeRow("README.md"))
            awaitNoTag(AppTags.STATUS_OPENING)
            // The next successful switch clears the message.
            switchProjectTo(other.path)
            awaitTag(TreeTags.treeRow("Other.kt"))
            awaitNoTag(AppTags.STATUS_ERROR)
        }
}
