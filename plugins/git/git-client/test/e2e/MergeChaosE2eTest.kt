package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FILE = "notes.txt"
private val RESOLVED = textOf("one", "two ours", "three", "four ours", "five", "six theirs", "")

/** The merge of a conflicted file, which Git runs on the server, while the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class MergeChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a merge opened while the network is down loads once it is back`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project, chaos = true)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.gitChange(FILE))

            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(GitTags.gitChange(FILE)).performClick()
            // Nothing is shown as loaded, and nothing pretends to be merged, while the sides cannot be read.
            assertTrue(
                app.project.editor.activeTab
                    ?.status !is TabStatus.Merge,
            )

            app.network.comeBack()
            awaitReconnected()
            awaitTag(EditorTags.MERGE)
            assertEquals(1, mergeOf(app).unresolved)
        }

    @Test
    fun `a server restart with the merge open leaves it usable, and Apply still resolves the file`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project, chaos = true)
            val merge = openMergeFromGitPanel(app, FILE)

            app.process.restart()
            awaitReconnected()
            onNodeWithTag(EditorTags.MERGE_ALL_OURS).performClick()
            awaitCondition { merge.unresolved == 0 }
            onNodeWithTag(EditorTags.MERGE_APPLY).performClick()

            awaitNoTag(EditorTags.MERGE)
            assertEquals(RESOLVED, project.read(FILE))
            assertEquals(emptyList(), project.unmergedPaths())
        }

    @Test
    fun `an Apply whose answer is lost resolves the file once and still closes the merge`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project, chaos = true)
            val merge = openMergeFromGitPanel(app, FILE)
            onNodeWithTag(EditorTags.MERGE_ALL_OURS).performClick()
            awaitCondition { merge.unresolved == 0 }

            // The file is resolved, then the connection breaks before the answer: the retry must not fail or redo.
            app.network.dropFromServer = true
            onNodeWithTag(EditorTags.MERGE_APPLY).performClick()
            awaitCondition { project.unmergedPaths().isEmpty() }
            app.network.cutAll()
            app.network.dropFromServer = false

            awaitNoTag(EditorTags.MERGE)
            assertEquals(RESOLVED, project.read(FILE))
            assertEquals(emptyList(), project.unmergedPaths())
        }

    @Test
    fun `a file another program resolved meanwhile is not overwritten by Apply`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project, chaos = true)
            val merge = openMergeFromGitPanel(app, FILE)
            onNodeWithTag(EditorTags.MERGE_ALL_OURS).performClick()
            awaitCondition { merge.unresolved == 0 }

            project.git("checkout", "--theirs", "--", FILE)
            project.git("add", FILE)
            val theirs = project.read(FILE)
            awaitCondition { app.project.git.status[FILE] != GitFileStatus.Conflicted }
            onNodeWithTag(EditorTags.MERGE_APPLY).performClick()

            awaitCondition { app.project.git.messageIsError }
            assertTrue(
                "no longer in conflict" in
                    app.project.git.message
                        .orEmpty(),
                app.project.git.message
                    .orEmpty(),
            )
            assertEquals(theirs, project.read(FILE))
            // The merge stays open with the decisions made: the user decides what to do.
            awaitTag(EditorTags.MERGE)
        }
}
