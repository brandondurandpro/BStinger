package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.GitFileStatus
import org.example.kotlineditor.state.MergeChoice
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FILE = "notes.txt"

/** The three-way merge of a conflicted file: three columns, one decision per conflict, then Apply. */
@OptIn(ExperimentalTestApi::class)
class MergeE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a conflicted file is listed as a conflict and opens its three-way merge`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            awaitCondition { app.project.git.status[FILE] == GitFileStatus.Conflicted }

            val merge = openMergeFromGitPanel(app, FILE)

            awaitTag(EditorTags.mergeTab(FILE))
            assertEquals(1, merge.unresolved)
            awaitTag(EditorTags.mergeHeading(merge.firstConflict()))
            // What only one side changed is taken without asking; the conflicted line shows the base until decided.
            assertEquals(
                textOf("one", "two", "three", "four ours", "five", "six theirs", ""),
                merge.resultLines().joinToString("\n"),
            )
            // One row per line of the longest column, aligned with its neighbours in the other two.
            awaitTag(EditorTags.mergeCell(2, 0), unmerged = true)
            awaitTag(EditorTags.mergeCell(2, 1), unmerged = true)
            awaitTag(EditorTags.mergeCell(2, 2), unmerged = true)
        }

    @Test
    fun `Apply waits for the last conflict, then writes the result and stages the file`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            val merge = openMergeFromGitPanel(app, FILE)

            onNodeWithTag(EditorTags.MERGE_APPLY).assertIsNotEnabled()
            awaitTag(EditorTags.mergeChoice(merge.firstConflict(), MergeChoice.Theirs.name)).performClick()
            awaitCondition { merge.unresolved == 0 }
            onNodeWithTag(EditorTags.MERGE_APPLY).assertIsEnabled()
            onNodeWithTag(EditorTags.MERGE_APPLY).performClick()

            awaitNoTag(EditorTags.MERGE)
            awaitCondition { app.project.git.status[FILE] != GitFileStatus.Conflicted }
            val expected = textOf("one", "two theirs", "three", "four ours", "five", "six theirs", "")
            assertEquals(expected, project.read(FILE))
            assertEquals(emptyList(), project.unmergedPaths())
            assertTrue(project.git("status", "--porcelain").lines().any { it.startsWith("M") && it.endsWith(FILE) })
            // The merge is still the user's to finish: Apply stages the file, it commits nothing.
            assertTrue(project.git("rev-parse", "-q", "--verify", "MERGE_HEAD").isNotBlank())
        }

    @Test
    fun `both sides, then the base, are choices of a conflict`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            val merge = openMergeFromGitPanel(app, FILE)
            val conflict = merge.firstConflict()

            awaitTag(EditorTags.mergeChoice(conflict, MergeChoice.Both.name)).performClick()
            awaitCondition { merge.choiceOf(conflict) == MergeChoice.Both }
            val both = textOf("one", "two ours", "two theirs", "three", "four ours", "five", "six theirs", "")
            assertEquals(both, merge.resultLines().joinToString("\n"))

            onNodeWithTag(EditorTags.mergeChoice(conflict, MergeChoice.Base.name)).performClick()
            awaitCondition { merge.choiceOf(conflict) == MergeChoice.Base }
            assertEquals(
                textOf("one", "two", "three", "four ours", "five", "six theirs", ""),
                merge.resultLines().joinToString("\n"),
            )
        }

    @Test
    fun `Accept all ours resolves every open conflict at once`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            val merge = openMergeFromGitPanel(app, FILE)

            onNodeWithTag(EditorTags.MERGE_ALL_OURS).performClick()
            awaitCondition { merge.unresolved == 0 }
            onNodeWithTag(EditorTags.MERGE_APPLY).performClick()
            awaitNoTag(EditorTags.MERGE)

            assertEquals(textOf("one", "two ours", "three", "four ours", "five", "six theirs", ""), project.read(FILE))
            assertEquals(emptyList(), project.unmergedPaths())
        }

    @Test
    fun `Cancel closes the merge and leaves the file conflicted on the disk`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val before = project.read(FILE)
            val app = launchWithProject(project)
            val merge = openMergeFromGitPanel(app, FILE)
            awaitTag(EditorTags.mergeChoice(merge.firstConflict(), MergeChoice.Ours.name)).performClick()
            awaitCondition { merge.unresolved == 0 }

            onNodeWithTag(EditorTags.MERGE_CANCEL).performClick()

            awaitNoTag(EditorTags.MERGE)
            assertEquals(before, project.read(FILE))
            assertEquals(listOf(FILE), project.unmergedPaths())
            assertEquals(GitFileStatus.Conflicted, app.project.git.status[FILE])
        }

    @Test
    fun `opening the merge again after Cancel starts from the files, not from the earlier decisions`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            val first = openMergeFromGitPanel(app, FILE)
            awaitTag(EditorTags.mergeChoice(first.firstConflict(), MergeChoice.Ours.name)).performClick()
            awaitCondition { first.unresolved == 0 }
            onNodeWithTag(EditorTags.MERGE_CANCEL).performClick()
            awaitNoTag(EditorTags.MERGE)

            awaitTag(GitTags.gitChange(FILE)).performClick()
            awaitTag(EditorTags.MERGE)
            awaitCondition {
                app.project.editor.activeTab
                    ?.status is TabStatus.Merge
            }

            assertEquals(1, mergeOf(app).unresolved)
        }

    @Test
    fun `clicking the conflict again reuses its merge tab`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            val merge = openMergeFromGitPanel(app, FILE)
            assertEquals(1, merge.unresolved)
            assertEquals(
                1,
                app.project.editor.allTabs
                    .count { it.isMerge },
            )
            // The same file again reuses its tab.
            awaitTag(GitTags.gitChange(FILE)).performClick()
            assertEquals(
                1,
                app.project.editor.allTabs
                    .count { it.isMerge },
            )
        }

    @Test
    fun `a file deleted on one side says so instead of showing columns`() =
        runComposeUiTest {
            project.file("gone.txt", MERGE_BASE)
            project.commitAll()
            val current = project.git("rev-parse", "--abbrev-ref", "HEAD").trim()
            project.git("checkout", "-q", "-b", "feature")
            project.git("rm", "-q", "gone.txt")
            project.git("commit", "-q", "-m", "delete")
            project.git("checkout", "-q", current)
            project.file("gone.txt", MERGE_OURS)
            project.git("commit", "-q", "-am", "edit")
            runCatching { project.git("merge", "--no-edit", "feature") }
            val app = launchWithProject(project)
            awaitCondition { app.project.git.status["gone.txt"] == GitFileStatus.Conflicted }

            onNodeWithTag(org.example.kotlineditor.ui.AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.gitChange("gone.txt")).performClick()

            awaitCondition {
                app.project.editor.activeTab
                    ?.status is TabStatus.Unsupported
            }
            val status =
                app.project.editor.activeTab
                    ?.status as TabStatus.Unsupported
            assertTrue("deleted" in status.message, status.message)
        }

    @Test
    fun `the result keeps the CRLF line endings of the file`() =
        runComposeUiTest {
            val crlf = { text: String -> text.replace("\n", "\r\n") }
            project.startConflictingMerge(FILE, crlf(MERGE_BASE), crlf(MERGE_OURS), crlf(MERGE_THEIRS))
            val app = launchWithProject(project)
            val merge = openMergeFromGitPanel(app, FILE)

            onNodeWithTag(EditorTags.MERGE_ALL_THEIRS).performClick()
            awaitCondition { merge.unresolved == 0 }
            onNodeWithTag(EditorTags.MERGE_APPLY).performClick()
            awaitNoTag(EditorTags.MERGE)

            val expected = crlf(textOf("one", "two theirs", "three", "four ours", "five", "six theirs", ""))
            assertEquals(expected, project.read(FILE))
        }

    @Test
    fun `applying a merge changes only the resolved file, keeping its executable bit`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val executable = project.root.resolve(FILE)
            val posix = Files.getFileStore(executable).supportsFileAttributeView("posix")
            if (posix) Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwxr-xr-x"))
            val app = launchWithProject(project)
            val merge = openMergeFromGitPanel(app, FILE)
            val before = DiskSnapshot.take(project)

            onNodeWithTag(EditorTags.MERGE_ALL_OURS).performClick()
            awaitCondition { merge.unresolved == 0 }
            onNodeWithTag(EditorTags.MERGE_APPLY).performClick()
            awaitNoTag(EditorTags.MERGE)
            awaitCondition { app.project.git.status[FILE] != GitFileStatus.Conflicted }

            before.assertUnchanged(project, except = setOf(FILE))
            if (posix) {
                assertEquals(PosixFilePermissions.fromString("rwxr-xr-x"), Files.getPosixFilePermissions(executable))
            }
        }

    @Test
    fun `opening a merge and cancelling it changes nothing on the disk`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            val before = DiskSnapshot.take(project)

            openMergeFromGitPanel(app, FILE)
            onNodeWithTag(EditorTags.MERGE_ALL_THEIRS).performClick()
            onNodeWithTag(EditorTags.MERGE_CANCEL).performClick()
            awaitNoTag(EditorTags.MERGE)

            before.assertUnchanged(project)
        }

    @Test
    fun `the history shows the commits of the file on each side, and a click opens the commit`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val ours = project.git("rev-parse", "HEAD").trim()
            val theirs = project.git("rev-parse", "feature").trim()
            val app = launchWithProject(project)
            openMergeFromGitPanel(app, FILE)
            awaitNoTag(EditorTags.MERGE_HISTORY)

            onNodeWithTag(EditorTags.MERGE_HISTORY_TOGGLE).performClick()

            awaitTag(EditorTags.mergeHistoryCommit("ours", ours))
            awaitTag(EditorTags.mergeHistoryCommit("theirs", theirs))
            // The commit before the two sides diverged belongs to neither list.
            val base = project.git("merge-base", "HEAD", "feature").trim()
            awaitNoTag(EditorTags.mergeHistoryCommit("ours", base))
            onNodeWithTag(EditorTags.mergeHistoryCommit("theirs", theirs)).performClick()
            awaitTag(EditorTags.commitDiffTab(theirs, FILE))

            app.project.editor.activeTab
                ?.let { assertEquals(theirs, it.commit?.hash) }
        }

    @Test
    fun `the history can be hidden again`() =
        runComposeUiTest {
            project.startConflictingMerge(FILE)
            val app = launchWithProject(project)
            openMergeFromGitPanel(app, FILE)
            onNodeWithTag(EditorTags.MERGE_HISTORY_TOGGLE).performClick()
            awaitTag(EditorTags.MERGE_HISTORY)

            onNodeWithTag(EditorTags.MERGE_HISTORY_TOGGLE).performClick()

            awaitNoTag(EditorTags.MERGE_HISTORY)
        }
}
