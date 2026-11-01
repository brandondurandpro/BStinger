package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.DiffKind
import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.GitTags
import org.example.kotlineditor.ui.Tags
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class GitPanelE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.dragToolWindowHeader(window: String, target: Offset) {
        val header = onNodeWithTag(Tags.toolHeader(window))
        val delta = target - header.centerInRoot()
        val steps = 12
        header.performMouseInput {
            moveTo(center)
            press()
            repeat(steps) { moveBy(delta / steps.toFloat()) }
            release()
        }
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.centerInRoot(): Offset {
        val bounds = getBoundsInRoot()
        return Offset((bounds.left.value + bounds.right.value) / 2f, (bounds.top.value + bounds.bottom.value) / 2f)
    }

    @Test
    fun `Alt+9 opens the Git panel below the project panel and lists the changes`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            launchWithProject(project)
            onNodeWithTag(TreeTags.TREE).performClick()
            onNodeWithTag(TreeTags.TREE).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Nine) } }
            awaitTag(GitTags.GIT_PANEL)
            awaitTag(GitTags.gitChange("README.md"))
            val projectPanel = onNodeWithTag(TreeTags.PROJECT_PANEL).getBoundsInRoot()
            val gitPanel = onNodeWithTag(GitTags.GIT_PANEL).getBoundsInRoot()
            assertTrue(gitPanel.top >= projectPanel.bottom, "Git panel $gitPanel is not below $projectPanel")
            assertEquals(gitPanel.left, projectPanel.left)
        }

    @Test
    fun `clicking a change opens a diff tab with removed and added lines`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.gitChange("README.md"))
            onNodeWithTag(GitTags.gitChange("README.md")).performClick()
            awaitTag(EditorTags.diffTab("README.md"))
            awaitTag(EditorTags.DIFF)
            awaitTag(EditorTags.diffLine(0))
            awaitCondition {
                (
                    app.project.editor.activeTab
                        ?.status as? TabStatus.Diff
                    )?.lines?.isNotEmpty() == true
            }
            val lines =
                (
                    app.project.editor.activeTab!!
                        .status as TabStatus.Diff
                    ).lines
            assertTrue(lines.any { it.kind == DiffKind.Removed && it.text == "# Demo" })
            assertTrue(lines.any { it.kind == DiffKind.Added && it.text == "# Changed" })
            // The hunk header carries no line number, changed lines are numbered on both sides.
            assertTrue(lines.any { it.kind == DiffKind.Removed && it.oldLine == 1 && it.newLine == -1 })
            assertTrue(lines.any { it.kind == DiffKind.Added && it.oldLine == -1 && it.newLine == 1 })
            assertEquals(
                "Diff: README.md",
                app.project.editor.activeTab
                    ?.title,
            )
        }

    @Test
    fun `the diff can be switched to a side-by-side layout and back`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.gitChange("README.md"))
            onNodeWithTag(GitTags.gitChange("README.md")).performClick()
            awaitTag(EditorTags.DIFF)
            awaitTag(EditorTags.diffLine(0))
            onNodeWithTag(EditorTags.DIFF_LAYOUT_TOGGLE).performClick()
            awaitTag(EditorTags.DIFF_SPLIT)
            awaitTag(EditorTags.diffSplitRow(0))
            // The removed line is on the left, the added one on the right.
            onNodeWithText("# Demo").assertIsDisplayed()
            onNodeWithText("# Changed").assertIsDisplayed()
            assertTrue(app.project.diffSideBySide)
            onNodeWithTag(EditorTags.DIFF_LAYOUT_TOGGLE).performClick()
            awaitTag(EditorTags.diffLine(0))
            awaitNoTag(EditorTags.DIFF_SPLIT)
            assertTrue(!app.project.diffSideBySide)
        }

    @Test
    fun `untracked files can be opened as a diff of additions`() =
        runComposeUiTest {
            project.commitAll()
            project.file("notes.txt", "first\nsecond\n")
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.gitChange("notes.txt"))
            onNodeWithTag(GitTags.gitChange("notes.txt")).performClick()
            awaitCondition {
                (
                    app.project.editor.activeTab
                        ?.status as? TabStatus.Diff
                    )?.lines
                    ?.count { it.kind == DiffKind.Added } == 2
            }
            awaitTag(EditorTags.DIFF)
        }

    @Test
    fun `dragging a panel header moves the panel to another dock but not its button`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_PANEL)
            val buttonBefore = onNodeWithTag(AppTags.TOOL_GIT).getBoundsInRoot()
            val workspace = onNodeWithTag(AppTags.WORKSPACE).getBoundsInRoot()
            val centerX = (workspace.left.value + workspace.right.value) / 2f
            val centerY = (workspace.top.value + workspace.bottom.value) / 2f
            dragToolWindowHeader("Git", Offset(workspace.right.value - 60f, centerY))
            awaitCondition { app.project.toolWindows.dockOf(ToolWindow.Git) == Dock.Right }
            onNodeWithTag(GitTags.GIT_PANEL).assertIsDisplayed()
            val moved = onNodeWithTag(GitTags.GIT_PANEL).getBoundsInRoot()
            assertTrue(moved.left.value > centerX, "Git panel $moved is not on the right of $workspace")
            assertEquals(buttonBefore, onNodeWithTag(AppTags.TOOL_GIT).getBoundsInRoot())
            // The project panel stays where it was, on the left.
            assertEquals(Dock.Left, app.project.toolWindows.dockOf(ToolWindow.Project))
            assertTrue(onNodeWithTag(TreeTags.PROJECT_PANEL).getBoundsInRoot().left.value < centerX)
        }

    @Test
    fun `dragging the Git panel to the top of its column puts it above the project panel`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_PANEL)
            val workspace = onNodeWithTag(AppTags.WORKSPACE).getBoundsInRoot()
            dragToolWindowHeader("Git", Offset(workspace.left.value + 120f, workspace.top.value + 40f))
            awaitCondition {
                app.project.toolWindows
                    .visibleIn(Dock.Left)
                    .first() == ToolWindow.Git
            }
            val git = onNodeWithTag(GitTags.GIT_PANEL).getBoundsInRoot()
            val tree = onNodeWithTag(TreeTags.PROJECT_PANEL).getBoundsInRoot()
            assertTrue(git.bottom <= tree.top, "Git panel $git is not above the project panel $tree")
        }

    @Test
    fun `dragging a panel header to the bottom docks it below the editors`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_PANEL)
            val workspace = onNodeWithTag(AppTags.WORKSPACE).getBoundsInRoot()
            val centerX = (workspace.left.value + workspace.right.value) / 2f
            val centerY = (workspace.top.value + workspace.bottom.value) / 2f
            dragToolWindowHeader("Git", Offset(centerX, workspace.bottom.value - 40f))
            awaitCondition { app.project.toolWindows.dockOf(ToolWindow.Git) == Dock.Bottom }
            val moved = onNodeWithTag(GitTags.GIT_PANEL).getBoundsInRoot()
            assertTrue(moved.top.value > centerY, "Git panel $moved is not at the bottom of $workspace")
            assertTrue(moved.left.value < centerX)
        }

    @Test
    fun `a project that is not a Git repository says so`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_NOT_A_REPOSITORY)
        }

    @Test
    fun `a repository without changes says so`() =
        runComposeUiTest {
            project.commitAll()
            launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_EMPTY)
        }

    @Test
    fun `the Git button hides and shows the panel`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_PANEL)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitNoTag(GitTags.GIT_PANEL)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_PANEL)
        }

    @Test
    fun `the gutter marks the lines changed since HEAD`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            launchWithProject(project)
            openFromTree("README.md")
            // The single line replaces three lines of HEAD: modified, with the removed block before it.
            awaitTag(EditorTags.changeMarker(0, "Modified"), unmerged = true)
            awaitTag(EditorTags.changeMarker(0, "Deleted"), unmerged = true)
        }

    @Test
    fun `saving a file recomputes the gutter marks of that file only`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project)
            val network = app.client.network

            fun diffs(direction: Direction) =
                network.snapshot().count { it.direction == direction && it.method == "GitDiff" }

            onNodeWithTag(TreeTags.TREE).performClick()
            onNodeWithTag(TreeTags.TREE).performKeyInput {
                withKeyDown(Key.CtrlLeft) {
                    withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.D) } }
                }
            }
            awaitCondition { network.recording }
            openFromTree("README.md")
            openFromTree("src/main/kotlin/App.kt")
            openFromTree("build.gradle.kts")
            awaitCondition { diffs(Direction.Received) == 3 }
            network.clear()

            onNodeWithTag(EditorTags.EDITOR).performClick()
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                pressKey(Key.X)
                withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
            }
            awaitCondition { app.project.git.status["build.gradle.kts"] != null }
            awaitCondition {
                app.project.editor.activeTab
                    ?.lineChanges
                    ?.isNotEmpty() == true
            }
            waitForIdle()
            // Three files are open, but only the saved one changed: its diff is the only one asked again.
            assertEquals(1, diffs(Direction.Sent))
        }

    @Test
    fun `Annotate shows the author and date of each line in the gutter`() =
        runComposeUiTest {
            project.commitAll()
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.GIT_BLAME).performClick()
            awaitTag(EditorTags.blame(0), unmerged = true)
            awaitCondition {
                app.project.editor.activeTab
                    ?.blame
                    ?.isNotEmpty() == true
            }
            val line =
                app.project.editor.activeTab
                    ?.blame
                    ?.first()
            assertEquals("Test", line?.author)
            val date = line?.date
            assertTrue(date != null && date.isNotEmpty(), "No blame date")
            val tab = app.project.editor.activeTab
            assertTrue(tab?.blameVisible == true)
            // Clicking Annotate again hides them.
            onNodeWithTag(GitTags.GIT_BLAME).performClick()
            awaitNoTag(EditorTags.blame(0))
        }

    @Test
    fun `the Commit button carries only the checked changes`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            project.file("notes.txt", "new\n")
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.gitChange("README.md"))
            awaitTag(GitTags.gitChange("notes.txt"))
            onNodeWithTag(GitTags.GIT_COMMIT).assertIsNotEnabled()
            awaitTag(GitTags.gitChangeCheck("README.md")).performClick()
            awaitCondition { app.project.git.selectedForCommit == setOf("README.md") }
            awaitTag(GitTags.GIT_COMMIT_MESSAGE).performTextInput("Update the readme")
            onNodeWithTag(GitTags.GIT_COMMIT).performClick()
            awaitCondition { !app.project.git.busy && app.project.git.status.keys == setOf("notes.txt") }
            assertEquals("Committed 1 file", app.project.git.message)
            assertEquals("Update the readme", project.lastCommitSubject())
        }

    @Test
    fun `the branch menu switches to another local branch`() =
        runComposeUiTest {
            project.commitAll()
            project.git("branch", "feature")
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitCondition { app.project.git.branches.contains("feature") }
            awaitTag(GitTags.GIT_BRANCH_BUTTON).performClick()
            awaitTag(GitTags.gitBranch("feature")).performClick()
            awaitCondition { app.project.git.currentBranch == "feature" }
            assertEquals("Switched to feature", app.project.git.message)
        }

    @Test
    fun `a diff tab is not restored in the session`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            awaitTag(GitTags.gitChange("README.md"))
            onNodeWithTag(GitTags.gitChange("README.md")).performClick()
            awaitTag(EditorTags.diffTab("README.md"))
            runOnUiThread { app.state.closeProject() }
            awaitTag(AppTags.WELCOME)
            onNodeWithTag(AppTags.recentProject(project.path)).performClick()
            awaitTag(TreeTags.treeRow("src"))
            assertTrue(
                app.project.editor.tabs
                    .none { it.isDiff },
            )
        }
}
