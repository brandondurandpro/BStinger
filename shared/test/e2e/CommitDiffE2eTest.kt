package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.DiffLine
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A click on a file changed by a commit, in the Branches window, opens the diff editor on that commit's change. */
@OptIn(ExperimentalTestApi::class)
class CommitDiffE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun hashOf(ref: String) = project.git("rev-parse", ref).trim()

    private fun ComposeUiTest.openCommit(app: E2eApp, hash: String) {
        awaitCondition { app.project.gitBranch != null }
        if (!app.project.toolWindows.isVisible(org.example.kotlineditor.state.ToolWindow.Branches)) {
            onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
        }
        awaitTag(ItemTags.historyCommit(hash)).performClick()
        awaitTag(Tags.HISTORY_DETAILS)
    }

    private fun ComposeUiTest.diffOf(app: E2eApp, hash: String, path: String): List<DiffLine> {
        awaitTag(Tags.commitDiffTab(hash, path))
        awaitTag(Tags.DIFF)
        val editor = app.project.editor
        awaitCondition { editor.activeTab?.status is TabStatus.Diff }
        val tab: EditorTab = app.project.editor.activeTab!!
        assertEquals(hash, tab.commit?.hash)
        return (tab.status as TabStatus.Diff).lines
    }

    @Test
    fun `a click on a changed file opens the diff of that commit, not of the working tree`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", textOf("# Demo", "First change", ""))
            project.git("commit", "-q", "-am", "First change")
            val first = hashOf("HEAD")
            project.file("README.md", textOf("# Demo", "First change", "Second change", ""))
            project.git("commit", "-q", "-am", "Second change")
            // An uncommitted edit too: the commit's diff must not show it.
            project.file("README.md", textOf("# Demo", "First change", "Second change", "Uncommitted", ""))
            val app = launchWithProject(project)

            openCommit(app, first)
            awaitTag(ItemTags.historyFile("README.md")).performClick()
            val lines = diffOf(app, first, "README.md")
            assertEquals(listOf("First change"), lines.filter { it.kind == DiffKind.Added }.map { it.text })
            assertTrue(lines.none { it.text == "Second change" || it.text == "Uncommitted" })
            val active = app.project.editor.activeTab!!
            val title = active.title
            assertTrue(title.endsWith("@ ${first.take(8)}"), title)
        }

    @Test
    fun `each commit opens its own tab, and opening one again reuses it`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", textOf("# Demo", "One", ""))
            project.git("commit", "-q", "-am", "One")
            val one = hashOf("HEAD")
            project.file("README.md", textOf("# Demo", "One", "Two", ""))
            project.git("commit", "-q", "-am", "Two")
            val two = hashOf("HEAD")
            val app = launchWithProject(project)

            openCommit(app, one)
            awaitTag(ItemTags.historyFile("README.md")).performClick()
            diffOf(app, one, "README.md")
            openCommit(app, two)
            awaitTag(ItemTags.historyFile("README.md")).performClick()
            val lines = diffOf(app, two, "README.md")
            assertEquals(listOf("Two"), lines.filter { it.kind == DiffKind.Added }.map { it.text })
            awaitTag(Tags.commitDiffTab(one, "README.md"))

            openCommit(app, one)
            awaitTag(ItemTags.historyFile("README.md")).performClick()
            diffOf(app, one, "README.md")
            val tabs = app.project.editor.allTabs
            assertEquals(2, tabs.count { it.commit != null })
            // Closing it works like any tab.
            onNodeWithTag(Tags.commitDiffTabClose(one, "README.md")).performClick()
            awaitNoTag(Tags.commitDiffTab(one, "README.md"))
        }

    @Test
    fun `renamed and deleted files open their diff, even when they no longer exist on disk`() =
        runComposeUiTest {
            project.commitAll()
            project.git("mv", "README.md", "GUIDE.md")
            project.file("GUIDE.md", textOf("# Demo guide", "", "A sample project.", ""))
            project.git("rm", "-q", "build.gradle.kts")
            project.git("add", "-A")
            project.git("commit", "-q", "-m", "Rename the readme, drop the build")
            val head = hashOf("HEAD")
            val app = launchWithProject(project)

            openCommit(app, head)
            // Listed as a rename: Git saw the same file under a new name.
            val history = app.project.history
            awaitCondition {
                history.details
                    ?.files
                    ?.any { it.oldPath == "README.md" } == true
            }
            awaitTag(ItemTags.historyFile("GUIDE.md")).performClick()
            val renamed = diffOf(app, head, "GUIDE.md")
            assertEquals(listOf("# Demo"), renamed.filter { it.kind == DiffKind.Removed }.map { it.text })
            assertEquals(listOf("# Demo guide"), renamed.filter { it.kind == DiffKind.Added }.map { it.text })

            onNodeWithTag(ItemTags.historyFile("build.gradle.kts")).performClick()
            val deleted = diffOf(app, head, "build.gradle.kts")
            assertTrue(deleted.any { it.kind == DiffKind.Removed })
            assertTrue(deleted.none { it.kind == DiffKind.Added })
        }

    @Test
    fun `saving the file does not change the diff of a commit`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", textOf("# Demo", "Committed", ""))
            project.git("commit", "-q", "-am", "Committed")
            val head = hashOf("HEAD")
            val app = launchWithProject(project)
            openCommit(app, head)
            awaitTag(ItemTags.historyFile("README.md")).performClick()
            val before = diffOf(app, head, "README.md")

            // The working tree and HEAD move on; the commit's diff stays what the commit did.
            project.file("README.md", textOf("# Demo", "Committed", "Later", ""))
            project.git("commit", "-q", "-am", "Later")
            onNodeWithTag(Tags.HISTORY_REFRESH).performClick()
            app.project.refreshGitStatus()
            val history = app.project.history
            awaitCondition { history.commits.firstOrNull()?.subject == "Later" }
            onNodeWithTag(Tags.commitDiffTab(head, "README.md")).performClick()
            assertEquals(before, diffOf(app, head, "README.md"))
        }
}
