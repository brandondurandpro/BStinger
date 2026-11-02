package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FILES = 300

/** The names of the expanded directories scrolled out of view stay pinned at the top of the project tree. */
@OptIn(ExperimentalTestApi::class)
class StickyTreeE2eTest {
    private val project = SampleProject.standard()
    private val rootName = project.root.fileName.toString()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun name(i: Int) = "huge/file${i.toString().padStart(4, '0')}.txt"

    // Launches with a "huge" directory of FILES files, expanded.
    private fun ComposeUiTest.launchWithHugeDirectory(): E2eApp {
        repeat(FILES) { project.file(name(it), "content $it") }
        val app = launchWithProject(project)
        onNodeWithTag(TreeTags.treeChevron("huge")).performClick()
        awaitTag(TreeTags.treeRow(name(0)))
        return app
    }

    private fun rowIndex(app: E2eApp, relativePath: String) =
        app.project.tree.rows
            .indexOfFirst { app.project.tree.relativePath(it.entry) == relativePath }

    // Scrolls the tree so that the row at [index] is the first one.
    private fun ComposeUiTest.scrollTreeTo(index: Int) {
        onNode(hasScrollToNodeAction() and hasAnyAncestor(hasTestTag(TreeTags.TREE))).performScrollToIndex(index)
        waitForIdle()
    }

    private fun SemanticsNodeInteraction.top() = fetchSemanticsNode().boundsInRoot.top

    private fun SemanticsNodeInteraction.bottom() = fetchSemanticsNode().boundsInRoot.bottom

    @Test
    fun `nothing is pinned while the tree is not scrolled`() =
        runComposeUiTest {
            launchWithHugeDirectory()
            assertTrue(onAllNodes(hasTestTagPrefix("tree.sticky:")).fetchSemanticsNodes().isEmpty())
        }

    @Test
    fun `scrolling into a directory pins its name and the root`() =
        runComposeUiTest {
            val app = launchWithHugeDirectory()
            scrollTreeTo(rowIndex(app, name(100)))
            awaitTag(TreeTags.treeSticky(rootName)).assertIsDisplayed()
            awaitTag(TreeTags.treeSticky("huge")).assertIsDisplayed()
            val root = onNodeWithTag(TreeTags.treeSticky(rootName))
            assertTrue(root.bottom() <= onNodeWithTag(TreeTags.treeSticky("huge")).top())
        }

    @Test
    fun `nested directories are pinned one under the other`() =
        runComposeUiTest {
            repeat(FILES) { project.file("src/main/kotlin/many/f$it.kt", "") }
            val app = launchWithProject(project)
            app.project.tree.reveal(project.root.resolve("src/main/kotlin/many/f0.kt").toString())
            awaitTag(TreeTags.treeRow("src/main/kotlin/many/f0.kt"))
            scrollTreeTo(rowIndex(app, "src/main/kotlin/many/f150.kt"))
            val stack = listOf(rootName, "src", "src/main", "src/main/kotlin", "src/main/kotlin/many")
            stack.forEach { awaitTag(TreeTags.treeSticky(it)) }
            val tops = stack.map { onNodeWithTag(TreeTags.treeSticky(it)).top() }
            assertEquals(tops.sorted(), tops, "headers are stacked from the root down")
        }

    @Test
    fun `scrolling past the end of a directory unpins it`() =
        runComposeUiTest {
            // Enough rows after "src" for the list to scroll it to the top.
            repeat(FILES / 2) { project.file("top$it.txt", "") }
            val app = launchWithHugeDirectory()
            scrollTreeTo(rowIndex(app, name(100)))
            awaitTag(TreeTags.treeSticky("huge"))
            scrollTreeTo(rowIndex(app, "src"))
            awaitNoTag(TreeTags.treeSticky("huge"))
            awaitTag(TreeTags.treeSticky(rootName))
        }

    @Test
    fun `clicking a pinned directory scrolls back to it and selects it`() =
        runComposeUiTest {
            val app = launchWithHugeDirectory()
            scrollTreeTo(rowIndex(app, name(200)))
            awaitTag(TreeTags.treeSticky("huge")).performClick()
            awaitNoTag(TreeTags.treeSticky("huge"))
            onNodeWithTag(TreeTags.treeRow("huge")).assertIsDisplayed()
            awaitCondition { app.project.tree.selectedPath == project.root.resolve("huge").toString() }
            // Its own row is under the pinned root, not hidden by it.
            assertTrue(
                onNodeWithTag(TreeTags.treeSticky(rootName)).bottom() <= onNodeWithTag(TreeTags.treeRow("huge")).top(),
            )
        }

    @Test
    fun `the mouse wheel over a pinned directory scrolls the tree`() =
        runComposeUiTest {
            val app = launchWithHugeDirectory()
            scrollTreeTo(rowIndex(app, name(100)))
            val row = awaitTag(TreeTags.treeRow(name(110)))
            val before = row.top()
            awaitTag(TreeTags.treeSticky("huge")).performMouseInput { scroll(3f) }
            awaitCondition {
                onAllNodesWithTag(
                    TreeTags.treeRow(name(110)),
                ).fetchSemanticsNodes().none { it.boundsInRoot.top >= before }
            }
        }

    @Test
    fun `moving the selection under the pinned directories scrolls it into view`() =
        runComposeUiTest {
            val app = launchWithHugeDirectory()
            scrollTreeTo(rowIndex(app, name(100)))
            awaitTag(TreeTags.treeSticky("huge"))
            // Rows 100 and 101 are under the root and "huge" headers: 102 is the first one in view.
            onNodeWithTag(TreeTags.treeRow(name(102))).performClick()
            onNodeWithTag(TreeTags.TREE).performKeyInput {
                pressKey(Key.DirectionUp)
                pressKey(Key.DirectionUp)
            }
            awaitCondition { app.project.tree.selectedPath == project.root.resolve(name(100)).toString() }
            awaitCondition {
                val headers = onAllNodes(hasTestTagPrefix("tree.sticky:")).fetchSemanticsNodes()
                val stickyBottom = headers.maxOfOrNull { it.boundsInRoot.bottom } ?: 0f
                onAllNodesWithTag(TreeTags.treeRow(name(100))).fetchSemanticsNodes().any {
                    it.boundsInRoot.top >= stickyBottom
                }
            }
        }
}
