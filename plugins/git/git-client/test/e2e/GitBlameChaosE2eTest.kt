package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Slow, but under the 1 s after which the chaos client treats a silent link as dead.
private const val SLOW_LINK_MS = 300L

/** The diff of a commit opened from the blame annotation of a line, while the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class GitBlameChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun head() = project.git("rev-parse", "HEAD").trim()

    // Two commits, the second adding line 1 of the readme; the blame shown.
    private fun ComposeUiTest.annotatedReadme(): E2eApp {
        project.file("README.md", "# Sample\n")
        project.commitAll()
        project.file("README.md", "# Sample\nHow to build it.\n")
        project.git("commit", "-q", "-a", "-m", "Explain how to build\n\nThe readme tells newcomers where to start.")
        val app = launchWithProject(project, chaos = true)
        openFromTree("README.md")
        onNodeWithTag(AppTags.TOOL_GIT).performClick()
        awaitTag(GitTags.GIT_BLAME).performClick()
        awaitTag(EditorTags.blame(1), unmerged = true)
        return app
    }

    private fun E2eApp.diffShown(): Boolean {
        val diff = project.editor.activeTab?.status as? TabStatus.Diff
        return diff != null && diff.lines.isNotEmpty()
    }

    @Test
    fun `a blame annotation clicked while the network is down opens the commit's diff once it is back`() =
        runComposeUiTest {
            val app = annotatedReadme()
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(EditorTags.blame(1), useUnmergedTree = true).performClick()
            // Nothing pretends to be loaded while the link is down.
            assertTrue(!app.diffShown())
            app.network.comeBack()
            awaitReconnected()
            awaitCondition { app.diffShown() }
            awaitTag(EditorTags.DIFF_COMMIT)
            assertEquals(
                head(),
                app.project.editor.activeTab
                    ?.commit
                    ?.hash,
            )
            assertTrue("Explain how to build" in textOf(EditorTags.DIFF_COMMIT_MESSAGE))
        }

    @Test
    fun `a blame annotation clicked when the link goes silent opens the diff once it carries data again`() =
        runComposeUiTest {
            val app = annotatedReadme()
            app.network.blackHole = true
            onNodeWithTag(EditorTags.blame(1), useUnmergedTree = true).performClick()
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            awaitCondition { app.diffShown() }
            assertEquals(
                head(),
                app.project.editor.activeTab
                    ?.commit
                    ?.hash,
            )
        }

    @Test
    fun `on a slow link two annotations clicked one after the other end on the second commit`() =
        runComposeUiTest {
            val app = annotatedReadme()
            app.network.latencyMs = SLOW_LINK_MS
            onNodeWithTag(EditorTags.blame(0), useUnmergedTree = true).performClick()
            openFromTree("README.md")
            awaitTag(EditorTags.blame(1), unmerged = true)
            onNodeWithTag(EditorTags.blame(1), useUnmergedTree = true).performClick()
            awaitCondition {
                app.project.editor.activeTab
                    ?.commit
                    ?.hash == head() &&
                    app.diffShown()
            }
            awaitTag(EditorTags.DIFF_COMMIT)
            assertTrue("Explain how to build" in textOf(EditorTags.DIFF_COMMIT_MESSAGE))
        }

    @Test
    fun `after the server restarts the annotations still open their commit`() =
        runComposeUiTest {
            val app = annotatedReadme()
            app.process.restart()
            awaitReconnected()
            onNodeWithTag(EditorTags.blame(1), useUnmergedTree = true).performClick()
            awaitCondition { app.diffShown() }
            awaitTag(EditorTags.DIFF_COMMIT)
            assertEquals(
                head(),
                app.project.editor.activeTab
                    ?.commit
                    ?.hash,
            )
        }
}
