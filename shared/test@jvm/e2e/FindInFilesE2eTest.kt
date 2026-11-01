package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.testkit.ControlledSearch
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Opens Find in Files with Ctrl+Shift+F from the project tree, as a user would. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openFindInFiles() {
    onNodeWithTag(Tags.TREE).performClick()
    onNodeWithTag(Tags.TREE).performKeyInput {
        withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
    }
    awaitTag(Tags.FIND_IN_FILES_INPUT)
}

/** Waits until the node tagged [tag] shows exactly [text]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitText(tag: String, text: String, timeout: Long = TIMEOUT) {
    awaitCondition(timeout) { runCatching { onNodeWithTag(tag).assertTextEquals(text) }.isSuccess }
}

/** The searches of every server the test started (a restart starts a new one), to hold, release and count them. */
class Searches {
    private val all = CopyOnWriteArrayList<ControlledSearch>()

    /** The searches of the server running now. */
    val current: ControlledSearch get() = all.last()

    /** Searches started by every server so far. */
    val started: Int get() = all.sumOf { it.started.get() }

    /** A server setup whose searches are controlled by this object; [setup] prepares each new one. */
    fun server(setup: ControlledSearch.() -> Unit = {}): ServerSetup =
        ServerSetup(wrapSearch = { search -> ControlledSearch(search).apply(setup).also { all += it } })
}

/** Find in Files streamed by the server: results shown as they come, stopped when no longer wanted. */
@OptIn(ExperimentalTestApi::class)
class FindInFilesE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the first matches are shown while the search goes on, then the final count`() =
        runComposeUiTest {
            val searches = Searches()
            launchWithProject(project, server = searches.server { hold() })
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            // The server sent its first match and waits: it is listed, and the popup says more may come.
            awaitText(Tags.FIND_IN_FILES_COUNT, "1 match in 1 file…")
            assertEquals(1, onAllNodes(hasTestTagPrefix("findInFiles.item:")).fetchSemanticsNodes().size)
            searches.current.release()
            awaitText(Tags.FIND_IN_FILES_COUNT, "2 matches in 2 files")
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            awaitTag(ItemTags.findInFilesItem("src/main/kotlin/App.kt", 7))
            awaitNoTag(Tags.FIND_IN_FILES_ENGINE)
        }

    @Test
    fun `matches are listed by file then line whatever order they arrive in`() =
        runComposeUiTest {
            project.file("a/z.txt", "needle\n")
            project.file("b/y.txt", textOf("x", "needle", "needle", ""))
            launchWithProject(project)
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("needle")
            awaitText(Tags.FIND_IN_FILES_COUNT, "3 matches in 2 files")
            val order =
                onAllNodes(hasTestTagPrefix("findInFiles.item:")).fetchSemanticsNodes().map { node ->
                    node.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag]
                }
            assertEquals(
                listOf(
                    ItemTags.findInFilesItem("a/z.txt", 0),
                    ItemTags.findInFilesItem("b/y.txt", 1),
                    ItemTags.findInFilesItem("b/y.txt", 2),
                ),
                order,
            )
        }

    @Test
    fun `closing the popup during a search stops it on the server`() =
        runComposeUiTest {
            val searches = Searches()
            launchWithProject(project, server = searches.server { hold() })
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            awaitText(Tags.FIND_IN_FILES_COUNT, "1 match in 1 file…")
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(Tags.FIND_IN_FILES)
            awaitCondition { searches.current.stopped.get() == 1 }
        }

    @Test
    fun `typing a new query stops the search of the previous one`() =
        runComposeUiTest {
            val searches = Searches()
            launchWithProject(project, server = searches.server { hold() })
            openFindInFiles()
            val input = onNodeWithTag(Tags.FIND_IN_FILES_INPUT)
            input.performTextInput("Hello")
            awaitText(Tags.FIND_IN_FILES_COUNT, "1 match in 1 file…")
            input.performTextClearance()
            input.performTextInput("shout")
            awaitCondition { searches.current.stopped.get() >= 1 }
            searches.current.release()
            awaitText(Tags.FIND_IN_FILES_COUNT, "3 matches in 2 files")
            awaitTag(ItemTags.findInFilesItem("src/main/kotlin/util/Strings.kt", 2))
            awaitNoTag(ItemTags.findInFilesItem("docs/guide.md", 1))
        }

    @Test
    fun `an empty query shows nothing and searches nothing`() =
        runComposeUiTest {
            val searches = Searches()
            launchWithProject(project, server = searches.server())
            openFindInFiles()
            val input = onNodeWithTag(Tags.FIND_IN_FILES_INPUT)
            input.performTextInput("Hello")
            awaitText(Tags.FIND_IN_FILES_COUNT, "2 matches in 2 files")
            input.performTextClearance()
            awaitText(Tags.FIND_IN_FILES_COUNT, "")
            awaitNoTag(ItemTags.findInFilesItem("docs/guide.md", 1))
            assertEquals(1, searches.started)
        }

    @Test
    fun `without ripgrep the built-in search answers and the popup says so`() =
        runComposeUiTest {
            launchWithProject(project, server = ServerSetup.withoutRipgrep())
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("Hello")
            awaitText(Tags.FIND_IN_FILES_COUNT, "2 matches in 2 files")
            awaitTag(Tags.FIND_IN_FILES_ENGINE)
            awaitTag(ItemTags.findInFilesItem("docs/guide.md", 1))
        }

    @Test
    fun `more matches than the limit are counted as a lower bound`() =
        runComposeUiTest {
            project.file("many.txt", "needle\n".repeat(1_200))
            launchWithProject(project)
            openFindInFiles()
            onNodeWithTag(Tags.FIND_IN_FILES_INPUT).performTextInput("needle")
            awaitText(Tags.FIND_IN_FILES_COUNT, "1000+ matches in 1 file")
        }
}
