package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEAMS = 500
private const val MEMBERS = 4
private const val MAX_COMPOSED_ROWS = 40
private const val TEAM_ROWS = "agent.team.row."

/**
 * Performance of the Teams window with [TEAMS] teams of [MEMBERS] members each: opening it, jumping to a team far down,
 * opening its form, duplicating a team among them. The list is virtualized, so only the visible rows are composed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamsPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val servers = TeamRegistry()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.close()
    }

    private fun manyTeams(): List<Team> = (0 until TEAMS).map { sampleTeam("Team ${it.toString().padStart(4, '0')}", MEMBERS) }

    @Test
    fun `the window opens, scrolls and edits fast with hundreds of teams, and composes only the visible rows`() =
        runComposeUiTest {
            val teams = manyTeams()
            val app = launchWithProject(project, teamPreferences(globalTeams(*teams.toTypedArray())), agents = servers.settings())
            startConversation(app)
            assertEquals(TEAMS, app.project.agent.teams.teams.size)
            measure("open the window on $TEAMS teams", budgetMillis = 3_000, budgetMegabytes = 100) {
                openTeams()
                awaitTag(AgentTags.agentTeam(teams.first().id))
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix(TEAM_ROWS)).fetchSemanticsNodes().size
            assertTrue(composed < MAX_COMPOSED_ROWS, "composed rows: $composed")
            val far = teams[TEAMS - 10]
            measure("jump to a team of $TEAMS", budgetMillis = 2_000, budgetMegabytes = 60) {
                awaitTag(AgentTags.AGENT_TEAMS_LIST).performScrollToIndex(TEAMS - 10)
                awaitTag(AgentTags.agentTeam(far.id))
                renderFrame()
            }
            measure("open the form of a team among $TEAMS", budgetMillis = 2_000, budgetMegabytes = 60) {
                awaitTag(AgentTags.agentTeamEdit(far.id)).performClick()
                awaitTag(AgentTags.agentTeamField(AgentTags.teamMember(0), "name"))
                renderFrame()
            }
            val composedAfter = onAllNodes(hasTestTagPrefix(TEAM_ROWS)).fetchSemanticsNodes().size
            assertTrue(composedAfter < MAX_COMPOSED_ROWS, "composed rows with a form open: $composedAfter")
            measure("duplicate a team among $TEAMS", budgetMillis = 2_000, budgetMegabytes = 60) {
                awaitTag(AgentTags.agentTeamDuplicate(far.id)).performClick()
                awaitCondition { app.project.agent.teams.teams.size == TEAMS + 1 }
                renderFrame()
            }
            assertEquals(1, onAllNodesWithTag(AgentTags.AGENT_TEAMS_LIST).fetchSemanticsNodes().size)
        }
}
