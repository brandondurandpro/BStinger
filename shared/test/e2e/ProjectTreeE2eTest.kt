package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class ProjectTreeE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun rowPaths(app: E2eApp) =
        app.project.tree.rows
            .map { app.project.tree.relativePath(it.entry) }

    @Test
    fun `root is expanded with directories first then files, sorted by name`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitTag(TreeTags.treeRow("logo.png"))
            assertEquals(
                listOf(
                    project.root.fileName.toString(),
                    ".git",
                    "docs",
                    "src",
                    "build.gradle.kts",
                    "logo.png",
                    "README.md",
                ),
                rowPaths(app),
            )
            onNodeWithTag(TreeTags.treeRow("README.md")).assertIsDisplayed()
        }

    @Test
    fun `clicking the chevron expands and collapses a directory`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(TreeTags.treeChevron("src")).performClick()
            awaitTag(TreeTags.treeRow("src/main")).assertIsDisplayed()
            awaitTag(TreeTags.treeRow("src/test"))
            onNodeWithTag(TreeTags.treeChevron("src")).performClick()
            awaitNoTag(TreeTags.treeRow("src/main"))
        }

    @Test
    fun `double clicking a directory toggles it`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(TreeTags.treeRow("docs")).performDoubleClick()
            awaitTag(TreeTags.treeRow("docs/guide.md")).assertIsDisplayed()
        }

    @Test
    fun `nested directories load lazily`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            // Nothing below src is listed until it is expanded.
            assertTrue(rowPaths(app).none { it.startsWith("src/") })
            openFromTree("src/main/kotlin/util/Strings.kt")
            assertTrue("src/main/kotlin/util/Strings.kt" in rowPaths(app))
        }

    @Test
    fun `single click selects a file without opening it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.treeRow("README.md")).performClick()
            waitForIdle()
            assertEquals(project.root.resolve("README.md").toString(), app.project.tree.selectedPath)
            assertTrue(
                app.project.editor.tabs
                    .isEmpty(),
            )
            onNodeWithTag(AppTags.EDITOR_EMPTY).assertIsDisplayed()
        }

    @Test
    fun `keyboard navigation expands directories and opens files`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.treeRow("docs")).performClick()
            onNodeWithTag(TreeTags.TREE).performKeyInput { pressKey(Key.DirectionRight) }
            awaitTag(TreeTags.treeRow("docs/guide.md"))
            onNodeWithTag(TreeTags.TREE).performKeyInput {
                pressKey(Key.DirectionRight) // moves to the first child
                pressKey(Key.Enter)
            }
            awaitTag(EditorTags.tab("docs/guide.md"))
            awaitEditorReady()
            assertEquals(
                "guide.md",
                app.project.editor.activeTab
                    ?.entry
                    ?.name,
            )
            // Opening moved the focus to the editor (like IntelliJ); click back into the tree.
            onNodeWithTag(TreeTags.treeRow("docs/guide.md")).performClick()
            // Left goes back to the parent, a second Left collapses it.
            onNodeWithTag(TreeTags.TREE).performKeyInput {
                pressKey(Key.DirectionLeft)
                pressKey(Key.DirectionLeft)
            }
            awaitNoTag(TreeTags.treeRow("docs/guide.md"))
            onNodeWithTag(TreeTags.TREE).performKeyInput { pressKey(Key.DirectionDown) }
            waitForIdle()
            assertEquals(project.root.resolve("src").toString(), app.project.tree.selectedPath)
        }

    @Test
    fun `a directory with thousands of files stays virtualized and scrollable`() =
        runComposeUiTest {
            repeat(5000) { project.file("huge/file${it.toString().padStart(4, '0')}.txt", "content $it") }
            val app = launchWithProject(project)
            onNodeWithTag(TreeTags.treeChevron("huge")).performClick()
            awaitTag(TreeTags.treeRow("huge/file0000.txt"))
            assertTrue(app.project.tree.rows.size > 5000)
            // Only a screenful of rows is actually composed.
            assertTrue(onAllNodesWithTag(TreeTags.treeRow("huge/file4999.txt")).fetchSemanticsNodes().isEmpty())
            val composed = onAllNodes(hasTestTagPrefix("tree:")).fetchSemanticsNodes().size
            assertTrue(composed < 100, "composed rows: $composed")
            onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(TreeTags.TREE)))
                .performScrollToNode(hasTestTag(TreeTags.treeRow("huge/file4999.txt")))
            onNodeWithTag(TreeTags.treeRow("huge/file4999.txt")).assertIsDisplayed()
        }

    @Test
    fun `the project view can be hidden and shown again`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_PROJECT).performClick()
            awaitNoTag(TreeTags.TREE)
            onNodeWithTag(AppTags.TOOL_PROJECT).performClick()
            awaitTag(TreeTags.TREE)
            onNodeWithTag(TreeTags.treeRow("src")).assertIsDisplayed()
        }

    @Test
    fun `refresh picks up files created on disk`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            project.file("NEW.txt", "new")
            app.project.tree.refresh()
            awaitTag(TreeTags.treeRow("NEW.txt")).assertIsDisplayed()
        }
}
