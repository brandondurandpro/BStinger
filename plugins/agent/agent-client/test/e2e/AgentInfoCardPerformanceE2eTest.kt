package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.refreshChanges
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

private const val COMMITS = 5_000
private const val CHANGED_FILES = 150
private const val SETUP_TIMEOUT = 120_000L
private val TEST_TIMEOUT = 5.minutes

/**
 * The "Informations" card on a repository of [COMMITS] commits, whose worktree has [CHANGED_FILES] uncommitted files:
 * what it asks the server (the distance from the base, the last commit, the status) must not grow with the history.
 */
@OptIn(ExperimentalTestApi::class)
class AgentInfoCardPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.shows(text: String): Boolean {
        val matcher = hasText(text, substring = true)
        return onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    private fun history() {
        project.commitAll()
        val script = StringBuilder()
        for (index in 0 until COMMITS) {
            val message = "commit $index"
            script.append("commit refs/heads/other\n")
            script.append("committer Test <test@example.com> ${1_700_000_000 + index} +0000\n")
            script.append("data ${message.toByteArray().size}\n$message\n")
            if (index == 0) script.append("from refs/heads/${project.git("branch", "--show-current").trim()}^0\n")
            val content = "$index\n"
            script.append("M 644 inline history.txt\n")
            script.append("data ${content.length}\n$content\n")
        }
        gitFastImport(project.root, script.toString())
    }

    @Test
    fun `the card reads its distance from the base and its changes fast on a long history`() =
        runComposeUiTest(testTimeout = TEST_TIMEOUT) {
            history()
            val app = launchWithProject(project, agentPreferences())
            awaitTag(AgentTags.MODE_AGENT).performClick()
            awaitTag(AgentTags.AGENT_NEW).performClick()
            awaitTag(AgentTags.agentBase("other")).performClick()
            awaitTag(AgentTags.AGENT_PROMPT)
            app.project.agent.conversationsVisible = false
            val conversation = checkNotNull(app.project.agent.current)
            awaitCondition(SETUP_TIMEOUT) { conversation.workspace != null && shows("up to date") }
            val directory = File(checkNotNull(conversation.workspace).root.path)
            repeat(CHANGED_FILES) { File(directory, "file-$it.txt").writeText("$it\n") }
            measure("read $CHANGED_FILES changes and the distance from the base on $COMMITS commits", 15_000, 400) {
                app.project.agent.refreshChanges(conversation)
                awaitCondition(SETUP_TIMEOUT) { shows("$CHANGED_FILES uncommitted files") }
                renderFrame()
            }
            gitIn(directory.toPath(), "add", "-A")
            gitIn(directory.toPath(), "commit", "-q", "-m", "all of them")
            measure("read the last commit and the distance after a commit", 8_000, 200) {
                app.project.agent.refreshChanges(conversation)
                awaitCondition(SETUP_TIMEOUT) { shows("1 ahead") && shows("all of them") }
                renderFrame()
            }
        }
}
