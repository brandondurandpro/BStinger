package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Project-wide searches, which run on the server, while the network fails and the server restarts. */
@OptIn(ExperimentalTestApi::class)
class SearchChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openFindInFiles() {
        onNodeWithTag(TreeTags.TREE).performClick()
        onNodeWithTag(TreeTags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        awaitTag(TreeTags.FIND_IN_FILES_INPUT)
    }

    // The project of the chaos searches: `needle` on enough lines, in enough files, for the stream to need several
    // batches whatever the engine (ripgrep closes a batch every few hits, the fallback every few dozen files); every
    // hit has its own line, since the engines count matching lines.
    private fun prepareMatches(): Int {
        val count = FILES * HITS_PER_FILE
        repeat(FILES) { file ->
            val lines = (0 until HITS_PER_FILE).joinToString("\n") { "needle $it in file $file" }
            project.file("src/hits/file$file.txt", lines + "\n")
        }
        return count
    }

    // A complete search of [app]: the final hits are exact (no batch was lost or reordered out) and all different
    // (no batch reached the client twice).
    private fun assertComplete(app: E2eApp, expected: Int) {
        val results = app.project.findInFiles.results
        assertEquals(expected, results.size, "every hit found")
        assertEquals(expected, results.distinctBy { it.entry.path to it.line }.size, "every hit is different")
    }

    @Test
    fun `Find in Files typed while the network is down lists the matches once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFindInFiles()
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            app.network.comeBack()
            awaitTag(TreeTags.findInFilesItem("docs/guide.md", 1))
            awaitTag(TreeTags.findInFilesItem("src/main/kotlin/App.kt", 7))
            onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextContains("2 matches in 2 files")
        }

    @Test
    fun `Find in Files on a slow network lists the matches of the last query typed`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = 300
            openFindInFiles()
            // Each key starts a search; the answers of the earlier queries arrive late and must not win.
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("Hel")
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("lo from")
            awaitTag(TreeTags.findInFilesItem("docs/guide.md", 1))
            awaitCondition {
                runCatching {
                    onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextEquals("1 match in 1 file")
                }.isSuccess
            }
            awaitNoTag(TreeTags.findInFilesItem("src/main/kotlin/App.kt", 7))
        }

    @Test
    fun `changing an option keeps the results on screen until the new ones replace them`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            openFindInFiles()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("hello")
            awaitTag(TreeTags.findInFilesItem("docs/guide.md", 1))
            app.network.latencyMs = 400
            awaitTag(TreeTags.FIND_IN_FILES_CASE).performClick()
            // The case now matters: "hello" matches less, but until its answer arrives the list is not blank.
            awaitCondition { !app.project.findInFiles.finished }
            val kept = app.project.findInFiles.results
            assertTrue(kept.isNotEmpty(), "the results vanished while searching")
            onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextEquals("Searching…")
            awaitCondition { app.project.findInFiles.finished }
        }

    @Test
    fun `a search cut mid-flight is replayed by the server, with neither a gap nor a duplicate`() =
        runComposeUiTest {
            val expected = prepareMatches()
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = 60
            openFindInFiles()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            awaitCondition {
                app.project.findInFiles.results
                    .isNotEmpty()
            }
            // The stream is still running: the connection breaks after the server ran its last request but before
            // the answers and the batches arrived, and the connection itself cuts with them.
            app.network.dropFromServer = true
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitCondition { app.project.findInFiles.finished }
            assertComplete(app, expected)
            awaitCondition {
                runCatching {
                    onNodeWithTag(TreeTags.FIND_IN_FILES_COUNT).assertTextEquals("$expected matches in $FILES files")
                }.isSuccess
            }
        }

    @Test
    fun `a search cut by a server restart runs again once, and completes`() =
        runComposeUiTest {
            val expected = prepareMatches()
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = 150
            openFindInFiles()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            awaitCondition {
                app.project.findInFiles.results
                    .isNotEmpty()
            }
            app.process.restart()
            awaitCondition { app.project.findInFiles.finished }
            assertComplete(app, expected)
            assertTrue(
                app.project.findInFiles.error == null,
                "a restart is not a failure: ${app.project.findInFiles.error}",
            )
        }

    @Test
    fun `a search whose server goes down mid-flight completes when it comes back`() =
        runComposeUiTest {
            val expected = prepareMatches()
            val app = launchWithProject(project, chaos = true)
            app.network.latencyMs = 150
            openFindInFiles()
            onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT).performTextInput("needle")
            // Not "and not finished": a fast runner may finish the search between two polls, and the test must not
            // depend on catching that window; the slow link only makes it wide.
            awaitCondition {
                app.project.findInFiles.results
                    .isNotEmpty()
            }
            app.network.goDown()
            awaitReconnecting()
            app.network.comeBack()
            awaitCondition { app.project.findInFiles.finished }
            assertComplete(app, expected)
        }

    private companion object {
        const val FILES = 200
        const val HITS_PER_FILE = 3
    }
}
