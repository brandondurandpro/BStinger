package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.state.relativePath
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val SLOW_LINK_MS = 400L

// Far less than the server's watch of the disk takes to report a file over [SLOW_LINK_MS].
private const val RIGHT_AWAY_MS = 200L

/** Browsing and changing the project tree while the network fails, or while other programs change the disk. */
@OptIn(ExperimentalTestApi::class)
class ProjectTreeChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a directory expanded while the network is down lists its files once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            val tree = app.project.tree
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(TreeTags.treeChevron("src")).performClick()
            awaitCondition { tree.rows.any { tree.relativePath(it.entry) == "src" && it.loading } }
            app.network.comeBack()
            awaitTag(TreeTags.treeRow("src/main"))
            awaitTag(TreeTags.treeRow("src/test"))
            awaitCondition { tree.rows.none { it.loading } }
        }

    @Test
    fun `a file whose creation answer is lost is created once, without an error`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            rightClickOn(TreeTags.treeRow("docs"))
            awaitTag(TreeTags.MENU_NEW_FILE).performClick()
            awaitTag(AppTags.FILE_DIALOG_INPUT).performTextInput("notes.md")
            // The request reaches the server, then the connection breaks before its answer arrives: the client sends
            // it again on a new connection.
            app.network.dropFromServer = true
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitCondition { project.root.resolve("docs/notes.md").exists() }
            app.network.cutAll()
            app.network.dropFromServer = false
            // A second execution would fail with "already exists": the server answers the retry from its cache.
            awaitNoTag(AppTags.FILE_DIALOG)
            assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_ERROR).fetchSemanticsNodes().isEmpty())
            awaitTag(EditorTags.tab("docs/notes.md"))
            awaitTag(TreeTags.treeRow("docs/notes.md"))
            assertEquals(1, onAllNodesWithTag(TreeTags.treeRow("docs/notes.md")).fetchSemanticsNodes().size)
        }

    @Test
    fun `a file whose creation answer is lost while the server restarts is created once, without an error`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            rightClickOn(TreeTags.treeRow("docs"))
            awaitTag(TreeTags.MENU_NEW_FILE).performClick()
            awaitTag(AppTags.FILE_DIALOG_INPUT).performTextInput("notes.md")
            app.network.dropFromServer = true
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitCondition { project.root.resolve("docs/notes.md").exists() }
            // The new server has no answer for the request: running it again would fail with "already exists".
            app.process.restart()
            app.network.dropFromServer = false
            app.network.cutAll()
            awaitNoTag(AppTags.FILE_DIALOG)
            assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_ERROR).fetchSemanticsNodes().isEmpty())
            awaitTag(EditorTags.tab("docs/notes.md"))
            awaitTag(TreeTags.treeRow("docs/notes.md"))
            assertEquals(1, onAllNodesWithTag(TreeTags.treeRow("docs/notes.md")).fetchSemanticsNodes().size)
        }

    @Test
    fun `a rename whose answer is lost while the server restarts is done once, without an error`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            rightClickOn(TreeTags.treeRow("README.md"))
            awaitTag(TreeTags.MENU_RENAME).performClick()
            awaitTag(AppTags.FILE_DIALOG_INPUT).performTextClearance()
            onNodeWithTag(AppTags.FILE_DIALOG_INPUT).performTextInput("INTRO.md")
            app.network.dropFromServer = true
            onNodeWithTag(AppTags.FILE_DIALOG_OK).performClick()
            awaitCondition { project.root.resolve("INTRO.md").exists() }
            app.process.restart()
            app.network.dropFromServer = false
            app.network.cutAll()
            awaitNoTag(AppTags.FILE_DIALOG)
            assertTrue(onAllNodesWithTag(AppTags.FILE_DIALOG_ERROR).fetchSemanticsNodes().isEmpty())
            awaitTag(TreeTags.treeRow("INTRO.md"))
            awaitNoTag(TreeTags.treeRow("README.md"))
        }

    @Test
    fun `a file deleted by another program says it cannot be read instead of loading forever`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            // Over a slow link the tree still shows the file when it is opened: the deletion has not reached it yet.
            app.network.latencyMs = 400
            project.root.resolve("README.md").deleteExisting()
            onNodeWithTag(TreeTags.treeRow("README.md")).performDoubleClick()
            awaitTag(EditorTags.EDITOR_MESSAGE).assertTextContains("Cannot read", substring = true)
            // Then the tree drops it.
            awaitNoTag(TreeTags.treeRow("README.md"))
        }

    @Test
    fun `a file revealed while the tree is listed again does not bring back the listing from before`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            awaitTag(TreeTags.treeRow("README.md"))
            val deepFile = "src/main/kotlin/util/Strings.kt"
            project.file("Added.md", "added\n")
            // Over a slow link the listing of the root answers first, then those of the directories of the file.
            app.network.latencyMs = SLOW_LINK_MS
            runOnUiThread {
                app.project.synchronize()
                app.project.tree.reveal(project.root.resolve(deepFile).toString())
            }
            awaitTag(TreeTags.treeRow(deepFile))
            // Shown already: waiting longer would let the server's watch of the disk repair the tree.
            awaitTag(TreeTags.treeRow("Added.md"), timeout = RIGHT_AWAY_MS)
        }
}
