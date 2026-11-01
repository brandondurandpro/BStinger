package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.GitTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The two sections of the Git panel (checked for the commit, other changes) and what the Commit button does. */
@OptIn(ExperimentalTestApi::class)
class GitCommitSelectionE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openGit(): E2eApp {
        val app = launchWithProject(project)
        onNodeWithTag(AppTags.TOOL_GIT).performClick()
        awaitTag(GitTags.GIT_PANEL)
        return app
    }

    @Test
    fun `ticking a change checks it for the commit and unticking unchecks it`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            project.file("notes.txt", "new\n")
            val app = openGit()
            awaitTag(GitTags.gitChange("README.md"))
            awaitTag(GitTags.gitChange("notes.txt"))
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitCondition { app.project.git.selectedForCommit == setOf("README.md") }
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitCondition {
                app.project.git.selectedForCommit
                    .isEmpty()
            }
        }

    @Test
    fun `the checked changes are committed together and the others stay`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            project.file("build.gradle.kts", "moved\n")
            project.file("notes.txt", "new\n")
            val app = openGit()
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitTag(GitTags.gitChangeCheck("notes.txt")).performClick()
            assertEquals(setOf("README.md", "notes.txt"), app.project.git.selectedForCommit)
            awaitTag(GitTags.GIT_COMMIT_MESSAGE).performTextInput("Two files")
            onNodeWithTag(GitTags.GIT_COMMIT).performClick()
            awaitCondition { !app.project.git.busy && app.project.git.status.keys == setOf("build.gradle.kts") }
            assertEquals("Committed 2 files", app.project.git.message)
            assertEquals("Two files", project.lastCommitSubject())
            assertEquals(0, app.project.git.selectedForCommit.size)
        }

    @Test
    fun `the selection survives a blank message and a commit whose file changed meanwhile`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = openGit()
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            // A blank message fails, and the checked file stays checked for the next try.
            onNodeWithTag(GitTags.GIT_COMMIT).performClick()
            awaitTag(GitTags.GIT_MESSAGE)
            assertEquals("A commit message is required", app.project.git.message)
            assertEquals(setOf("README.md"), app.project.git.selectedForCommit)
            // The same selection then commits without ticking again.
            awaitTag(GitTags.GIT_COMMIT_MESSAGE).performTextInput("Update the readme")
            onNodeWithTag(GitTags.GIT_COMMIT).performClick()
            awaitCondition {
                !app.project.git.busy &&
                    app.project.git.status
                        .isEmpty()
            }
            assertEquals("Update the readme", project.lastCommitSubject())
        }

    @Test
    fun `files that leave the status are unchecked by the next refresh`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            project.file("notes.txt", "new\n")
            val app = openGit()
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitTag(GitTags.gitChangeCheck("notes.txt")).performClick()
            assertEquals(setOf("README.md", "notes.txt"), app.project.git.selectedForCommit)
            // Another program reverts the file on disk; the refresh of the panel drops it from the selection.
            project.git("checkout", "--", "README.md")
            onNodeWithTag(GitTags.GIT_REFRESH).performClick()
            awaitCondition { app.project.git.selectedForCommit == setOf("notes.txt") }
            awaitCondition { app.project.git.status.keys == setOf("notes.txt") }
        }

    @Test
    fun `a deleted file and an untracked file can be checked and committed`() =
        runComposeUiTest {
            project.commitAll()
            Files.delete(project.root.resolve("README.md"))
            project.file("notes.txt", "new\n")
            val app = openGit()
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitTag(GitTags.gitChangeCheck("notes.txt")).performClick()
            awaitTag(GitTags.GIT_COMMIT_MESSAGE).performTextInput("Reshape the project")
            onNodeWithTag(GitTags.GIT_COMMIT).performClick()
            awaitCondition {
                !app.project.git.busy &&
                    app.project.git.status
                        .isEmpty()
            }
            assertEquals("Reshape the project", project.lastCommitSubject())
            // The committed files carry the deletion and the addition, nothing of another file.
            val names = project.git("show", "--name-status", "--format=").trim().lines()
            assertEquals(listOf("D" + '\t' + "README.md", "A" + '\t' + "notes.txt"), names)
        }

    @Test
    fun `the master checkbox checks every change, and unticks them all once checked`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            project.file("build.gradle.kts", "moved\n")
            project.file("notes.txt", "new\n")
            val app = openGit()
            // One row is ticked first: the master checkbox then ticks only what is left.
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitCondition { app.project.git.selectedForCommit == setOf("README.md") }
            awaitTag(GitTags.GIT_SELECT_ALL).performClick()
            awaitCondition {
                app.project.git.selectedForCommit == setOf("README.md", "build.gradle.kts", "notes.txt")
            }
            awaitTag(GitTags.GIT_SELECT_ALL).performClick()
            awaitCondition {
                app.project.git.selectedForCommit
                    .isEmpty()
            }
        }

    @Test
    fun `the Commit button stays disabled while nothing is checked, and the message is kept`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = openGit()
            awaitTag(GitTags.gitChange("README.md"))
            awaitTag(GitTags.GIT_COMMIT_MESSAGE).performTextInput("Update the readme")
            onNodeWithTag(GitTags.GIT_COMMIT).assertIsNotEnabled()
            awaitNoTag(GitTags.GIT_MESSAGE)
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitCondition { app.project.git.selectedForCommit == setOf("README.md") }
            assertEquals("Update the readme", app.project.git.commitMessage)
        }
}
