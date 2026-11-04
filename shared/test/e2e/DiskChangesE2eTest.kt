package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.io.path.deleteExisting
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Other programs change the disk while the window keeps the focus (a terminal, a build, an AI agent, `git` in a
 * shell): the tree, the open files and Git follow, without a refresh.
 */
@OptIn(ExperimentalTestApi::class)
class DiskChangesE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.expand(directory: String, child: String) {
        onNodeWithTag(TreeTags.treeChevron(directory)).performClick()
        awaitTag(TreeTags.treeRow(child))
    }

    private fun ComposeUiTest.awaitLine(index: Int, text: String) {
        awaitCondition {
            runCatching {
                onNodeWithTag(EditorTags.line(index)).assertTextContains(text, substring = true)
            }.isSuccess
        }
    }

    private fun ComposeUiTest.openGitPanel() {
        onNodeWithTag(TreeTags.TREE).performClick()
        onNodeWithTag(TreeTags.TREE).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Nine) } }
        awaitTag(GitTags.GIT_PANEL)
    }

    @Test
    fun `a file created by another program shows in the tree`() =
        runComposeUiTest {
            launchWithProject(project)
            expand("docs", "docs/guide.md")
            project.file("docs/notes.md", "Notes\n")
            project.file("Added.kt", "class Added\n")
            awaitTag(TreeTags.treeRow("docs/notes.md"))
            awaitTag(TreeTags.treeRow("Added.kt"))
        }

    @Test
    fun `a file deleted by another program leaves the tree`() =
        runComposeUiTest {
            launchWithProject(project)
            expand("docs", "docs/guide.md")
            project.root.resolve("docs/guide.md").deleteExisting()
            project.root.resolve("README.md").deleteExisting()
            awaitNoTag(TreeTags.treeRow("docs/guide.md"))
            awaitNoTag(TreeTags.treeRow("README.md"))
        }

    @Test
    fun `a directory created with files by another program lists them once expanded`() =
        runComposeUiTest {
            launchWithProject(project)
            project.file("generated/deep/One.kt", "class One\n")
            project.file("generated/Two.kt", "class Two\n")
            awaitTag(TreeTags.treeRow("generated"))
            expand("generated", "generated/Two.kt")
            expand("generated/deep", "generated/deep/One.kt")
            // Created later, in a directory the watch only discovered.
            project.file("generated/deep/Three.kt", "class Three\n")
            awaitTag(TreeTags.treeRow("generated/deep/Three.kt"))
        }

    @Test
    fun `an open file changed by another program shows its new text`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("docs/guide.md")
            project.file("docs/guide.md", "Rewritten outside\n")
            awaitLine(0, "Rewritten outside")
        }

    @Test
    fun `an open file with unsaved edits keeps them when another program changes it`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            typeCode("z")
            project.file("README.md", "changed on disk\n")
            // The witness: the tree saw the same burst of changes.
            project.file("Witness.kt", "class Witness\n")
            awaitTag(TreeTags.treeRow("Witness.kt"))
            waitForIdle()
            val document =
                checkNotNull(
                    app.project.editor.activeTab
                        ?.document,
                )
            assertEquals("z# Demo", document.lines[0])
        }

    @Test
    fun `a file changed by another program shows in the Git panel`() =
        runComposeUiTest {
            project.commitAll()
            launchWithProject(project)
            openGitPanel()
            project.file("README.md", "# Changed outside\n")
            awaitTag(GitTags.gitChange("README.md"))
        }

    @Test
    fun `a commit made in a terminal empties the Git panel`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            launchWithProject(project)
            openGitPanel()
            awaitTag(GitTags.gitChange("README.md"))
            project.git("commit", "-q", "-am", "outside")
            awaitNoTag(GitTags.gitChange("README.md"))
        }

    @Test
    fun `a branch checked out in a terminal shows in the toolbar`() =
        runComposeUiTest {
            project.commitAll()
            launchWithProject(project)
            awaitTag(GitTags.GIT_BRANCH)
            project.git("checkout", "-q", "-b", "feature/outside")
            awaitCondition {
                onAllNodes(
                    androidx.compose.ui.test
                        .hasText("feature/outside"),
                ).fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("feature/outside").assertExists()
        }

    @Test
    fun `a checkout in a terminal reloads the open files it changed`() =
        runComposeUiTest {
            project.commitAll()
            project.git("checkout", "-q", "-b", "other")
            project.file("docs/guide.md", "Guide of the other branch\n")
            project.git("commit", "-q", "-am", "other")
            project.git("checkout", "-q", "-")
            launchWithProject(project)
            openFromTree("docs/guide.md")
            awaitLine(0, "Guide")
            project.git("checkout", "-q", "other")
            awaitLine(0, "Guide of the other branch")
        }

    // Opens README.md, types in it without saving, then another program rewrites it.
    private fun ComposeUiTest.conflictOnReadme() {
        openFromTree("README.md")
        onNodeWithTag(EditorTags.EDITOR).performClick()
        onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
        typeCode("z")
        project.file("README.md", "# Written by another program\n")
        awaitTag(EditorTags.DISK_CONFLICT)
    }

    @Test
    fun `unsaved edits of a file another program changed are not saved over it until the user chooses`() =
        runComposeUiTest {
            launchWithProject(project)
            conflictOnReadme()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.S) } }
            // The witness: a later change is seen, so the save had its chance.
            project.file("Witness.kt", "class Witness\n")
            awaitTag(TreeTags.treeRow("Witness.kt"))
            waitForIdle()
            assertEquals("# Written by another program\n", project.read("README.md"))
        }

    @Test
    fun `in a conflict, Load from Disk shows what the other program wrote`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            conflictOnReadme()
            onNodeWithTag(EditorTags.DISK_RELOAD).performClick()
            awaitLine(0, "# Written by another program")
            awaitNoTag(EditorTags.DISK_CONFLICT)
            assertEquals(
                false,
                app.project.editor.activeTab
                    ?.document
                    ?.isModified,
            )
        }

    @Test
    fun `in a conflict, Keep My Changes saves the edits over what the other program wrote`() =
        runComposeUiTest {
            launchWithProject(project)
            conflictOnReadme()
            onNodeWithTag(EditorTags.DISK_KEEP).performClick()
            awaitCondition { project.read("README.md").startsWith("z# Demo") }
            awaitNoTag(EditorTags.DISK_CONFLICT)
        }

    @Test
    fun `a file with a backslash in its name created by another program shows in the tree`() =
        runComposeUiTest {
            // A backslash is a character of a name on Linux and macOS, a separator on Windows.
            if (!System.getProperty("os.name").startsWith("Windows")) {
                launchWithProject(project)
                expand("docs", "docs/guide.md")
                project.file("docs/back\\slash.md", "Notes\n")
                awaitTag(TreeTags.treeRow("docs/back\\slash.md"))
            }
        }
}
