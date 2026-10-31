package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import org.example.kotlineditor.state.BranchSection
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LOCALS = 60

/** The local and remote headers of the Branches window's branch list stay at the top while its rows scroll. */
@OptIn(ExperimentalTestApi::class)
class BranchesStickyHeadersE2eTest {
    private val project = SampleProject.standard()
    private val extras = ArrayList<Path>()

    @AfterTest
    fun cleanup() {
        project.delete()
        extras.forEach { it.toFile().deleteRecursively() }
    }

    // Enough local branches, and as many remote ones, for both sections to scroll well past the viewport.
    private fun repository(): String {
        project.commitAll()
        val default = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
        val remote = Files.createTempDirectory("kotlin-editor-remote").also { extras.add(it) }
        gitIn(remote, "init", "-q", "--bare")
        project.git("remote", "add", "origin", remote.toString())
        repeat(LOCALS) { project.git("branch", "local-${it.toString().padStart(3, '0')}") }
        project.git("push", "-q", "-u", "origin", "--all")
        return default
    }

    private fun ComposeUiTest.openBranches(app: E2eApp) {
        awaitCondition { app.project.gitBranchInfos.size > LOCALS }
        onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
        awaitTag(Tags.BRANCHES_PANEL)
        awaitTag(ItemTags.historyBranch("local-000"))
        // The remote branches are loaded by their own request; wait for them before scrolling to their section.
        awaitCondition { app.project.history.remoteBranches.size > LOCALS }
    }

    private fun ComposeUiTest.topOf(tag: String): Dp = onNodeWithTag(tag).getBoundsInRoot().top

    private fun ComposeUiTest.exists(tag: String): Boolean =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.assertSameTop(expected: Dp, tag: String) {
        val actual = topOf(tag)
        assertEquals(expected.value, actual.value, 1f, "$tag should stay at $expected, was $actual")
    }

    @Test
    fun `the local then the remote header stay at the top while their branches scroll under them`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project)
            openBranches(app)
            val local = ItemTags.historySection(BranchSection.Local.name)
            val remote = ItemTags.historySection(BranchSection.Remote.name)
            val top = topOf(local)
            val list = onNodeWithTag(Tags.HISTORY_BRANCH_LIST)

            list.performScrollToIndex(LOCALS / 2)
            waitForIdle()
            assertTrue(!exists(ItemTags.historyBranch("local-000")), "the local branches must have scrolled")
            assertSameTop(top, local)

            // The remote header takes the top when its section arrives, pushing the local one out of view.
            list.performScrollToIndex(LOCALS + 2 + LOCALS / 2)
            waitForIdle()
            assertSameTop(top, remote)
            assertTrue(!exists(local) || topOf(local).value < top.value, "the local header should be pushed out")

            // A stuck header is still a header: a click folds its section.
            onNodeWithTag(remote).performClick()
            awaitNoTag(ItemTags.historyBranch("origin/local-030"))
            onNodeWithTag(remote).performClick()
            list.performScrollToIndex(LOCALS + 2 + LOCALS / 2)
            awaitTag(ItemTags.historyBranch("origin/local-030"))

            // Back at the top, the local header is in its natural place again.
            list.performScrollToIndex(0)
            waitForIdle()
            assertSameTop(top, local)
        }

    @Test
    fun `a filter that shortens the list brings the stuck header back under the search field`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project)
            openBranches(app)
            val local = ItemTags.historySection(BranchSection.Local.name)
            val top = topOf(local)
            val list = onNodeWithTag(Tags.HISTORY_BRANCH_LIST)

            list.performScrollToIndex(LOCALS / 2)
            waitForIdle()
            assertSameTop(top, local)

            // One local and one remote branch left: the list no longer scrolls, the header is back in place.
            onNodeWithTag(Tags.HISTORY_BRANCH_SEARCH).performTextInput("local-003")
            awaitCondition { app.project.history.branchQuery == "local-003" }
            waitForIdle()
            onNodeWithTag(local).assertTextContains("1", substring = true)
            assertSameTop(top, local)

            // A filter that matches nothing keeps both headers, with no rows under them.
            onNodeWithTag(Tags.HISTORY_BRANCH_SEARCH).performTextInput("nothing matches this")
            awaitCondition {
                app.project.history.branchQuery
                    .endsWith("nothing matches this")
            }
            waitForIdle()
            awaitTag(local).assertTextContains("0", substring = true)
            awaitTag(ItemTags.historySection(BranchSection.Remote.name))
            assertSameTop(top, local)
        }

    @Test
    fun `a folded local section keeps its header at the top until the remote one replaces it`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project)
            openBranches(app)
            val local = ItemTags.historySection(BranchSection.Local.name)
            val remote = ItemTags.historySection(BranchSection.Remote.name)
            val top = topOf(local)
            val list = onNodeWithTag(Tags.HISTORY_BRANCH_LIST)

            onNodeWithTag(local).performClick()
            awaitNoTag(ItemTags.historyBranch("local-000"))
            assertSameTop(top, local)

            // With no local rows to hold it, the folded header scrolls away and the remote one takes the top.
            list.performScrollToIndex(1 + LOCALS / 2)
            waitForIdle()
            assertSameTop(top, remote)
            assertTrue(!exists(local) || topOf(local).value < top.value, "the folded local header should be pushed out")
            assertTrue(!exists(ItemTags.historyBranch("local-000")), "the local section must stay folded")
        }

    @Test
    fun `a filtered list still pins its headers while it scrolls`() =
        runComposeUiTest {
            repository()
            val app = launchWithProject(project)
            openBranches(app)
            val local = ItemTags.historySection(BranchSection.Local.name)
            val remote = ItemTags.historySection(BranchSection.Remote.name)
            val top = topOf(local)
            val list = onNodeWithTag(Tags.HISTORY_BRANCH_LIST)

            onNodeWithTag(Tags.HISTORY_BRANCH_SEARCH).performTextInput("local-00")
            awaitCondition { app.project.history.branchQuery == "local-00" }
            waitForIdle()
            onNodeWithTag(local).assertTextContains("10", substring = true)

            // Ten local and ten remote branches left: still taller than the list, so it scrolls.
            list.performScrollToIndex(10)
            waitForIdle()
            assertTrue(!exists(ItemTags.historyBranch("local-000")), "the filtered local branches must have scrolled")
            assertSameTop(top, local)

            list.performScrollToIndex(11 + 5)
            waitForIdle()
            assertSameTop(top, remote)
        }
}
