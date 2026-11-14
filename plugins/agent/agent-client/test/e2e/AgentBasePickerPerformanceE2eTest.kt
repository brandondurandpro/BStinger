package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val BRANCHES = 3_000
private const val MAX_COMPOSED_ROWS = 60
private const val BRANCH_ROWS = "agent.basePicker.branch:"

/**
 * Performance of the question "from which branch?" on a repository with [BRANCHES] local and as many remote branches:
 * opening it and filtering it. The list is virtualized, so only the visible rows are composed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentBasePickerPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun createBranches() {
        project.commitAll()
        val head = project.git("rev-parse", "HEAD").trim()
        repeat(BRANCHES) { index ->
            val name = "perf/branch-${index.toString().padStart(4, '0')}"
            for (place in listOf("heads", "remotes/origin")) {
                val ref = project.root.resolve(".git/refs/$place/$name")
                ref.parent.createDirectories()
                ref.writeText("$head\n")
            }
        }
    }

    @Test
    fun `the picker opens and filters fast with thousands of branches, and composes only the visible rows`() =
        runComposeUiTest {
            createBranches()
            val app = launchWithProject(project, agentPreferences())
            awaitTag(AgentTags.MODE_AGENT).performClick()
            measure("open the picker on ${2 * BRANCHES} branches", budgetMillis = 8_000, budgetMegabytes = 300) {
                awaitTag(AgentTags.AGENT_NEW).performClick()
                awaitTag(AgentTags.agentBase("origin/perf/branch-0000"))
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix(BRANCH_ROWS)).fetchSemanticsNodes().size
            assertTrue(composed < MAX_COMPOSED_ROWS, "composed rows: $composed")
            measure("filter ${2 * BRANCHES} branches", budgetMillis = 5_000, budgetMegabytes = 200) {
                awaitTag(AgentTags.AGENT_BASE_SEARCH).performTextInput("branch-2999")
                awaitNoTag(AgentTags.agentBase("origin/perf/branch-0000"))
                renderFrame()
            }
            assertTrue(app.project.agent.conversations.isEmpty(), "browsing the branches starts nothing")
        }
}
