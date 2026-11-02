package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A click on the blame annotation of a line opens the diff of its commit, the commit recalled above it. */
@OptIn(ExperimentalTestApi::class)
class GitBlameE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // Two commits: the first line comes from `init`, the second from a commit with a long message.
    private fun ComposeUiTest.annotatedReadme(): E2eApp {
        project.file("README.md", "# Sample\n")
        project.commitAll()
        project.file("README.md", "# Sample\nHow to build it.\n")
        project.git("commit", "-q", "-a", "-m", "Explain how to build\n\nThe readme tells newcomers where to start.")
        val app = launchWithProject(project)
        openFromTree("README.md")
        onNodeWithTag(AppTags.TOOL_GIT).performClick()
        awaitTag(GitTags.GIT_BLAME).performClick()
        awaitTag(EditorTags.blame(1), unmerged = true)
        return app
    }

    @Test
    fun `a click on the annotation of a line opens the diff of its commit with the whole message`() =
        runComposeUiTest {
            val app = annotatedReadme()
            onNodeWithTag(EditorTags.blame(1), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.DIFF_COMMIT)
            val head = project.git("rev-parse", "HEAD").trim()
            val tab = app.project.editor.activeTab
            assertEquals(head, tab?.commit?.hash)
            assertEquals(head.take(8), textOf(EditorTags.DIFF_COMMIT_HASH))
            assertEquals("Test", textOf(EditorTags.DIFF_COMMIT_AUTHOR))
            val message = textOf(EditorTags.DIFF_COMMIT_MESSAGE)
            assertTrue("Explain how to build" in message, message)
            assertTrue("The readme tells newcomers where to start." in message, message)
            // The diff itself: the line the commit added.
            awaitCondition {
                onAllNodes(hasTestTagPrefix("diff.line:"), true)
                    .fetchSemanticsNodes()
                    .any { it.config[androidx.compose.ui.semantics.SemanticsProperties.TestTag].endsWith(":Added") }
            }
        }

    @Test
    fun `the first line opens the first commit, and a line not committed yet opens nothing`() =
        runComposeUiTest {
            val app = annotatedReadme()
            onNodeWithTag(EditorTags.blame(0), useUnmergedTree = true).performClick()
            awaitTag(EditorTags.DIFF_COMMIT)
            assertEquals("init", textOf(EditorTags.DIFF_COMMIT_MESSAGE))
            // Back to the file, changed but not saved to a commit: its new line has no commit.
            openFromTree("README.md")
            project.file("README.md", listOf("# Sample", "How to build it.", "Not committed.", "").joinToString("\n"))
            app.project.editor.reloadBlames()
            awaitTag(EditorTags.blame(2), unmerged = true)
            val tabs = app.project.editor.allTabs.size
            onNodeWithTag(EditorTags.blame(2), useUnmergedTree = true).performClick()
            waitForIdle()
            assertEquals(tabs, app.project.editor.allTabs.size)
            assertTrue(onAllNodes(hasTestTag(EditorTags.DIFF)).fetchSemanticsNodes().isEmpty())
        }
}
