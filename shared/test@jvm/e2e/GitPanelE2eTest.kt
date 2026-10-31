package org.example.kotlineditor.e2e

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
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
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
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

    private fun git(vararg args: String) {
        val process =
            ProcessBuilder(listOf("git", "-c", "user.name=Test", "-c", "user.email=test@example.com") + args)
                .directory(project.root.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }

    private fun commitAll() {
        git("init", "-q")
        // The app commits and pulls without the test's -c options: a runner has no identity, and Windows converts EOLs.
        git("config", "user.name", "Test")
        git("config", "user.email", "test@example.com")
        git("config", "core.autocrlf", "false")
        git("add", "-A")
        git("commit", "-q", "-m", "init")
    }

    private fun lastCommitSubject(): String {
        val process =
            ProcessBuilder("git", "log", "-1", "--format=%s")
                .directory(project.root.toFile())
                .redirectErrorStream(true)
                .start()
        val output = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "git log failed: $output" }
        return output.trim()
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
            commitAll()
            project.file("README.md", "# Changed\n")
            launchWithProject(project)
            onNodeWithTag(Tags.TREE).performClick()
            onNodeWithTag(Tags.TREE).performKeyInput { withKeyDown(Key.AltLeft) { pressKey(Key.Nine) } }
            awaitTag(Tags.GIT_PANEL)
            awaitTag(ItemTags.gitChange("README.md"))
            val projectPanel = onNodeWithTag(Tags.PROJECT_PANEL).getBoundsInRoot()
            val gitPanel = onNodeWithTag(Tags.GIT_PANEL).getBoundsInRoot()
            assertTrue(gitPanel.top >= projectPanel.bottom, "Git panel $gitPanel is not below $projectPanel")
            assertEquals(gitPanel.left, projectPanel.left)
        }

    @Test
    fun `clicking a change opens a diff tab with removed and added lines`() =
        runComposeUiTest {
            commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("README.md"))
            onNodeWithTag(ItemTags.gitChange("README.md")).performClick()
            awaitTag(Tags.diffTab("README.md"))
            awaitTag(Tags.DIFF)
            awaitTag(Tags.diffLine(0))
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
            commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("README.md"))
            onNodeWithTag(ItemTags.gitChange("README.md")).performClick()
            awaitTag(Tags.DIFF)
            awaitTag(Tags.diffLine(0))
            onNodeWithTag(Tags.DIFF_LAYOUT_TOGGLE).performClick()
            awaitTag(Tags.DIFF_SPLIT)
            awaitTag(Tags.diffSplitRow(0))
            // The removed line is on the left, the added one on the right.
            onNodeWithText("# Demo").assertIsDisplayed()
            onNodeWithText("# Changed").assertIsDisplayed()
            assertTrue(app.project.diffSideBySide)
            onNodeWithTag(Tags.DIFF_LAYOUT_TOGGLE).performClick()
            awaitTag(Tags.diffLine(0))
            awaitNoTag(Tags.DIFF_SPLIT)
            assertTrue(!app.project.diffSideBySide)
        }

    @Test
    fun `untracked files can be opened as a diff of additions`() =
        runComposeUiTest {
            commitAll()
            project.file("notes.txt", "first\nsecond\n")
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("notes.txt"))
            onNodeWithTag(ItemTags.gitChange("notes.txt")).performClick()
            awaitCondition {
                (
                    app.project.editor.activeTab
                        ?.status as? TabStatus.Diff
                    )?.lines
                    ?.count { it.kind == DiffKind.Added } == 2
            }
            awaitTag(Tags.DIFF)
        }

    @Test
    fun `dragging a panel header moves the panel to another dock but not its button`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_PANEL)
            val buttonBefore = onNodeWithTag(Tags.TOOL_GIT).getBoundsInRoot()
            val workspace = onNodeWithTag(Tags.WORKSPACE).getBoundsInRoot()
            val centerX = (workspace.left.value + workspace.right.value) / 2f
            val centerY = (workspace.top.value + workspace.bottom.value) / 2f
            dragToolWindowHeader("Git", Offset(workspace.right.value - 60f, centerY))
            awaitCondition { app.project.toolWindows.dockOf(ToolWindow.Git) == Dock.Right }
            onNodeWithTag(Tags.GIT_PANEL).assertIsDisplayed()
            val moved = onNodeWithTag(Tags.GIT_PANEL).getBoundsInRoot()
            assertTrue(moved.left.value > centerX, "Git panel $moved is not on the right of $workspace")
            assertEquals(buttonBefore, onNodeWithTag(Tags.TOOL_GIT).getBoundsInRoot())
            // The project panel stays where it was, on the left.
            assertEquals(Dock.Left, app.project.toolWindows.dockOf(ToolWindow.Project))
            assertTrue(onNodeWithTag(Tags.PROJECT_PANEL).getBoundsInRoot().left.value < centerX)
        }

    @Test
    fun `dragging the Git panel to the top of its column puts it above the project panel`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_PANEL)
            val workspace = onNodeWithTag(Tags.WORKSPACE).getBoundsInRoot()
            dragToolWindowHeader("Git", Offset(workspace.left.value + 120f, workspace.top.value + 40f))
            awaitCondition {
                app.project.toolWindows
                    .visibleIn(Dock.Left)
                    .first() == ToolWindow.Git
            }
            val git = onNodeWithTag(Tags.GIT_PANEL).getBoundsInRoot()
            val tree = onNodeWithTag(Tags.PROJECT_PANEL).getBoundsInRoot()
            assertTrue(git.bottom <= tree.top, "Git panel $git is not above the project panel $tree")
        }

    @Test
    fun `dragging a panel header to the bottom docks it below the editors`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_PANEL)
            val workspace = onNodeWithTag(Tags.WORKSPACE).getBoundsInRoot()
            val centerX = (workspace.left.value + workspace.right.value) / 2f
            val centerY = (workspace.top.value + workspace.bottom.value) / 2f
            dragToolWindowHeader("Git", Offset(centerX, workspace.bottom.value - 40f))
            awaitCondition { app.project.toolWindows.dockOf(ToolWindow.Git) == Dock.Bottom }
            val moved = onNodeWithTag(Tags.GIT_PANEL).getBoundsInRoot()
            assertTrue(moved.top.value > centerY, "Git panel $moved is not at the bottom of $workspace")
            assertTrue(moved.left.value < centerX)
        }

    @Test
    fun `a project that is not a Git repository says so`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_NOT_A_REPOSITORY)
        }

    @Test
    fun `a repository without changes says so`() =
        runComposeUiTest {
            commitAll()
            launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_EMPTY)
        }

    @Test
    fun `the Git button hides and shows the panel`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_PANEL)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitNoTag(Tags.GIT_PANEL)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_PANEL)
        }

    @Test
    fun `the gutter marks the lines changed since HEAD`() =
        runComposeUiTest {
            commitAll()
            project.file("README.md", "# Changed\n")
            launchWithProject(project)
            openFromTree("README.md")
            // The single line replaces three lines of HEAD: modified, with the removed block before it.
            awaitTag(Tags.changeMarker(0, "Modified"), unmerged = true)
            awaitTag(Tags.changeMarker(0, "Deleted"), unmerged = true)
        }

    @Test
    fun `saving a file recomputes the gutter marks of that file only`() =
        runComposeUiTest {
            commitAll()
            val app = launchWithProject(project)
            val network = app.client.network

            fun diffs(direction: Direction) =
                network.snapshot().count { it.direction == direction && it.method == "GitDiff" }

            onNodeWithTag(Tags.TREE).performClick()
            onNodeWithTag(Tags.TREE).performKeyInput {
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

            onNodeWithTag(Tags.EDITOR).performClick()
            onNodeWithTag(Tags.EDITOR).performKeyInput {
                pressKey(Key.X)
                withKeyDown(Key.CtrlLeft) { pressKey(Key.S) }
            }
            awaitCondition { app.project.gitStatus["build.gradle.kts"] != null }
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
            commitAll()
            val app = launchWithProject(project)
            openFromTree("README.md")
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(Tags.GIT_BLAME).performClick()
            awaitTag(Tags.blame(0), unmerged = true)
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
            onNodeWithTag(Tags.GIT_BLAME).performClick()
            awaitNoTag(Tags.blame(0))
        }

    @Test
    fun `the Commit button stages and commits the changes`() =
        runComposeUiTest {
            commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("README.md"))
            awaitTag(Tags.GIT_COMMIT_MESSAGE).performTextInput("Update the readme")
            onNodeWithTag(Tags.GIT_COMMIT).performClick()
            awaitCondition { !app.project.gitBusy && app.project.gitStatus.isEmpty() }
            assertEquals("Committed", app.project.gitMessage)
            assertEquals("Update the readme", lastCommitSubject())
        }

    @Test
    fun `the branch menu switches to another local branch`() =
        runComposeUiTest {
            commitAll()
            git("branch", "feature")
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitCondition { app.project.gitBranches.contains("feature") }
            awaitTag(Tags.GIT_BRANCH_BUTTON).performClick()
            awaitTag(ItemTags.gitBranch("feature")).performClick()
            awaitCondition { app.project.gitBranch == "feature" }
            assertEquals("Switched to feature", app.project.gitMessage)
        }

    @Test
    fun `a diff tab is not restored in the session`() =
        runComposeUiTest {
            commitAll()
            project.file("README.md", "# Changed\n")
            val app = launchWithProject(project)
            onNodeWithTag(Tags.TOOL_GIT).performClick()
            awaitTag(ItemTags.gitChange("README.md"))
            onNodeWithTag(ItemTags.gitChange("README.md")).performClick()
            awaitTag(Tags.diffTab("README.md"))
            runOnUiThread { app.state.closeProject() }
            awaitTag(Tags.WELCOME)
            onNodeWithTag(ItemTags.recentProject(project.path)).performClick()
            awaitTag(Tags.treeRow("src"))
            assertTrue(
                app.project.editor.tabs
                    .none { it.isDiff },
            )
        }
}
