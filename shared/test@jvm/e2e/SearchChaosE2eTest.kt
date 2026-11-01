package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Project-wide searches, which run on the server, while the network fails. */
@OptIn(ExperimentalTestApi::class)
class SearchChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `Find in Files typed while the network is down lists the matches once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFindInFiles()
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            app.network.comeBack()
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            awaitTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7))
            onNodeWithTag(Tags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files")
        }

    @Test
    fun `Find in Files on a slow network lists the matches of the last query typed`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = 300
            openFindInFiles()
            // Each key starts a search; the answers of the earlier queries arrive late and must not win.
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hel")
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("lo from")
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            awaitCondition {
                runCatching {
                    onNodeWithTag(Tags.FIND_IN_FILES_COUNT).assertTextEquals("1 match in 1 file")
                }.isSuccess
            }
            awaitNoTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7))
        }

    @Test
    fun `a search cut by the network goes on where it was once the network is back`() =
        runComposeUiTest {
            val searches = Searches()
            val app = launchWithProject(project, chaos = true, server = searches.server { hold() })
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            awaitText(Tags.FIND_IN_FILES_COUNT, "1 match in 1 file…")
            app.network.goDown()
            awaitReconnecting()
            // The server finds the rest while the client cannot hear it.
            searches.current.release()
            app.network.comeBack()
            awaitReconnected()
            awaitText(Tags.FIND_IN_FILES_COUNT, "2 matches in 2 files")
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            awaitTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7))
            assertEquals(2, onAllNodes(hasTestTagPrefix("findInFiles.item:")).fetchSemanticsNodes().size)
            assertEquals(1, searches.started, "the search ran once")
        }

    @Test
    fun `a search whose results go silent comes back complete after the link is replaced`() =
        runComposeUiTest {
            val searches = Searches()
            val app = launchWithProject(project, chaos = true, server = searches.server { hold() })
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            awaitText(Tags.FIND_IN_FILES_COUNT, "1 match in 1 file…")
            app.network.blackHole = true
            searches.current.release()
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            awaitText(Tags.FIND_IN_FILES_COUNT, "2 matches in 2 files")
            assertEquals(2, onAllNodes(hasTestTagPrefix("findInFiles.item:")).fetchSemanticsNodes().size)
        }

    @Test
    fun `a search whose start answer is lost runs only once`() =
        runComposeUiTest {
            val searches = Searches()
            val app = launchWithProject(project, chaos = true, server = searches.server())
            openFindInFiles()
            // The request reaches the server, which starts searching; its answer and results are lost.
            app.network.dropFromServer = true
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello from")
            awaitCondition { searches.started == 1 }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitText(Tags.FIND_IN_FILES_COUNT, "1 match in 1 file")
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            assertEquals(1, searches.started)
        }

    @Test
    fun `a search lost by a restarting server starts again and completes`() =
        runComposeUiTest {
            val searches = Searches()
            val app = launchWithProject(project, chaos = true, server = searches.server { hold() })
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            awaitText(Tags.FIND_IN_FILES_COUNT, "1 match in 1 file…")
            app.process.restart()
            // The new server lost the search: the popup asks it again from scratch.
            awaitCondition { searches.started == 2 }
            searches.current.release()
            awaitText(Tags.FIND_IN_FILES_COUNT, "2 matches in 2 files")
            assertEquals(2, onAllNodes(hasTestTagPrefix("findInFiles.item:")).fetchSemanticsNodes().size)
        }
}
