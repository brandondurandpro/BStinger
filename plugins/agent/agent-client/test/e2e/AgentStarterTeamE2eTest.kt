package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.DEFAULT_AGENT
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.STARTER_TEAM_NAME
import org.example.kotlineditor.state.toPreferences
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The team every user starts with: the ready-made one, on Claude, added once at the first start. A team deleted is not
 * brought back, and a user who already has teams gets none added.
 */
@OptIn(ExperimentalTestApi::class)
class AgentStarterTeamE2eTest {
    private val project = SampleProject.standard()
    private val servers = TeamRegistry()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.close()
    }

    // The preferences of a first start: the ready-made team was not added yet.
    private fun firstStart() =
        InMemoryPreferenceStore(agentPreferences().load() + (PreferenceKeys.AGENT_TEAMS_SEEDED to "false"))

    @Test
    fun `a first start has the ready-made team, on Claude, and it is not added again once deleted`() =
        runComposeUiTest {
            val store = firstStart()
            val app = launchWithProject(project, store, agents = servers.settings())
            startConversation(app)
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.teams.teams.size == 1 }
            val saved = app.project.agent.teams.teams.single()
            assertTrue(saved.global)
            assertEquals(STARTER_TEAM_NAME, saved.team.name)
            val providers = (saved.team.members + saved.team.manager).map { it.agent.registry }.toSet()
            assertEquals(setOf<String?>(DEFAULT_AGENT), providers)
            // The team is listed in the Teams window, and chosen from the picker.
            openTeams()
            awaitTag(AgentTags.agentTeam(saved.team.id))
            app.project.agent.teams.remove(saved.team.id)
            // What the next start reads: the team is gone, and it is known to have been added already.
            val next = app.project.agent.toPreferences()
            assertEquals("true", next[PreferenceKeys.AGENT_TEAMS_SEEDED])
            assertTrue(STARTER_TEAM_NAME !in next.getValue(PreferenceKeys.AGENT_TEAMS))
        }

    @Test
    fun `a team deleted is not brought back at the next start`() =
        runComposeUiTest {
            val seeded = InMemoryPreferenceStore(agentPreferences().load() + (PreferenceKeys.AGENT_TEAMS_SEEDED to "true"))
            val app = launchWithProject(project, seeded, agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAMS_EMPTY)
            assertTrue(app.project.agent.teams.teams.isEmpty())
        }
}
