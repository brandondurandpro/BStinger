package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TabStatus
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.ui.ItemTags
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Open File button (and F4) of the diff editor: from the changes of a file, to the file itself. */
@OptIn(ExperimentalTestApi::class)
class DiffOpenFileE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openWorkingTreeDiff(path: String) {
        onNodeWithTag(Tags.TOOL_GIT).performClick()
        awaitTag(ItemTags.gitChange(path)).performClick()
        awaitTag(Tags.diffTab(path))
        awaitTag(Tags.DIFF_OPEN_FILE)
    }

    private fun ComposeUiTest.awaitCaretIn(app: E2eApp, path: String, line: Int) {
        awaitTag(Tags.tab(path))
        val editor = app.project.editor
        awaitCondition {
            val tab = editor.activeTab
            tab != null && !tab.isDiff && tab.relativePath == path && tab.document?.caret == TextPos(line, 0)
        }
    }

    @Test
    fun `Open File in the diff of a change opens the file at its first changed line`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", textOf("# Demo", "", "A sample project, changed.", ""))
            val app = launchWithProject(project)
            openWorkingTreeDiff("README.md")

            onNodeWithTag(Tags.DIFF_OPEN_FILE).performClick()
            awaitCaretIn(app, "README.md", line = 2)
            // The diff tab stays open next to the file.
            awaitTag(Tags.diffTab("README.md"))
        }

    @Test
    fun `F4 in a diff tab opens the file, and reuses its tab when it is already open`() =
        runComposeUiTest {
            project.commitAll()
            val app = project.root.resolve("src/main/kotlin/App.kt").toFile()
            project.file("src/main/kotlin/App.kt", app.readText() + "// added\n")
            val ide = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(Tags.tab("src/main/kotlin/App.kt"))
            openWorkingTreeDiff("src/main/kotlin/App.kt")

            onNodeWithTag(Tags.DIFF).performKeyInput { pressKey(Key.F4) }
            awaitCaretIn(ide, "src/main/kotlin/App.kt", line = app.readLines().lastIndex)
            val tabs = ide.project.editor.allTabs
            val textTabs = tabs.count { !it.isDiff && it.relativePath == "src/main/kotlin/App.kt" }
            assertEquals(1, textTabs, "the open tab is reused")
        }

    @Test
    fun `Open File in the diff of a commit opens the current file`() =
        runComposeUiTest {
            project.commitAll()
            project.file("README.md", textOf("# Demo", "", "A sample project.", "Added by the commit", ""))
            project.git("commit", "-q", "-am", "Add a line")
            val commit = project.git("rev-parse", "HEAD").trim()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranch != null }
            onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
            awaitTag(ItemTags.historyCommit(commit)).performClick()
            awaitTag(ItemTags.historyFile("README.md")).performClick()
            awaitTag(Tags.commitDiffTab(commit, "README.md"))

            onNodeWithTag(Tags.DIFF_OPEN_FILE).performClick()
            awaitCaretIn(app, "README.md", line = 3)
            val opened = app.project.editor.activeTab!!
            assertEquals("Added by the commit", opened.document!!.lines[3])
        }

    @Test
    fun `Open File on a file the commit deleted says it cannot be read, and keeps the diff`() =
        runComposeUiTest {
            project.commitAll()
            project.git("rm", "-q", "build.gradle.kts")
            project.git("commit", "-q", "-m", "Drop the build")
            val commit = project.git("rev-parse", "HEAD").trim()
            val app = launchWithProject(project)
            awaitCondition { app.project.gitBranch != null }
            onNodeWithTag(Tags.TOOL_BRANCHES).performClick()
            awaitTag(ItemTags.historyCommit(commit)).performClick()
            awaitTag(ItemTags.historyFile("build.gradle.kts")).performClick()
            awaitTag(Tags.DIFF_OPEN_FILE).performClick()

            awaitTag(Tags.tab("build.gradle.kts"))
            val editor = app.project.editor
            awaitCondition { editor.activeTab?.status is TabStatus.Unsupported }
            val message = (editor.activeTab!!.status as TabStatus.Unsupported).message
            assertTrue(message.startsWith("Cannot read file"), message)
            awaitTag(Tags.commitDiffTab(commit, "build.gradle.kts"))
        }
}
