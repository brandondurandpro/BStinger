package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val TEAMS = 500
private const val MAX_COMPOSED_ROWS = 20
private const val TEAM_CHOICES = "agent.teamPicker.team."

/**
 * Performance of the team picker with [TEAMS] teams: opening its menu, jumping to a team far down it and choosing it.
 * The list of the menu is lazy, so only the rows shown are composed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamPickerPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun manyTeams(): List<Team> = (0 until TEAMS).map { sampleTeam("Team ${it.toString().padStart(4, '0')}", 3) }

    @Test
    fun `the menu opens fast with hundreds of teams and composes only the rows it shows`() =
        runComposeUiTest {
            val teams = manyTeams()
            val preferences = InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + globalTeams(*teams.toTypedArray()))
            val app = launchWithProject(project, preferences)
            val conversation = startConversation(app)
            assertEquals(TEAMS, app.project.agent.teams.teams.size)
            awaitTag(AgentTags.AGENT_PERMISSIONS, timeout = AGENT_TIMEOUT)
            measure("open the team menu on $TEAMS teams", budgetMillis = 2_000, budgetMegabytes = 60) {
                awaitTag(AgentTags.AGENT_TEAM_PICKER).performClick()
                awaitTag(AgentTags.agentTeamChoice(teams.first().id))
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix(TEAM_CHOICES)).fetchSemanticsNodes().size
            assertTrue(composed < MAX_COMPOSED_ROWS, "composed rows: $composed")
            val far = teams[TEAMS - 10]
            measure("jump to a team of $TEAMS in the menu", budgetMillis = 2_000, budgetMegabytes = 60) {
                awaitTag(AgentTags.AGENT_TEAM_PICKER_LIST).performScrollToIndex(TEAMS - 10)
                awaitTag(AgentTags.agentTeamChoice(far.id))
                renderFrame()
            }
            measure("choose a team among $TEAMS", budgetMillis = 2_000, budgetMegabytes = 60) {
                awaitTag(AgentTags.agentTeamChoice(far.id)).performClick()
                awaitCondition { conversation.teamId == far.id }
                awaitNoTag(AgentTags.AGENT_MODEL)
                renderFrame()
            }
        }
}
