package org.example.kotlineditor.e2e

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

private const val LINES = 5_000

/** The blame of a file of 5,000 lines and the diff of a commit that changed all of them. */
@OptIn(ExperimentalTestApi::class)
class GitBlamePerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the diff of a commit of 5,000 lines opens quickly from the blame`() =
        runComposeUiTest {
            project.file("big.txt", (1..LINES).joinToString("\n", postfix = "\n") { "line $it" })
            project.commitAll()
            project.file("big.txt", (1..LINES).joinToString("\n", postfix = "\n") { "changed line $it" })
            project.git("commit", "-q", "-a", "-m", "Change every line\n\nAll $LINES of them.")
            val app = launchWithProject(project)
            openFromTree("big.txt")
            onNodeWithTag(AppTags.TOOL_GIT).performClick()
            measure("blame of $LINES lines", budgetMillis = 10_000) {
                awaitTag(GitTags.GIT_BLAME).performClick()
                awaitTag(EditorTags.blame(0), unmerged = true)
                renderFrame()
            }
            measure("diff of a commit changing $LINES lines, from the blame", budgetMillis = 10_000) {
                onNodeWithTag(EditorTags.blame(0), useUnmergedTree = true).performClick()
                awaitCondition {
                    val diff =
                        app.project.editor.activeTab
                            ?.status as? TabStatus.Diff
                    diff != null && diff.lines.isNotEmpty()
                }
                awaitTag(EditorTags.DIFF_COMMIT)
                renderFrame()
            }
        }
}
