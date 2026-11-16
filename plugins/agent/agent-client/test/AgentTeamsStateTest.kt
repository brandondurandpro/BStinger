package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTeamsStateTest {
    private val provider = AgentCommand(emptyList(), registry = "claude-acp")

    private fun agent(id: String, name: String) = TeamAgent(id, name, "role of $name", provider, mapOf("model" to "opus"), listOf("/s/a"), "Prompt")

    private fun team(id: String, name: String = "Team $id") = Team(id, name, agent("m$id", "Lead"), listOf(agent("a$id", "Coder")))

    private fun teams(saved: Map<String, String> = emptyMap()) = AgentTeams(saved, "/work/project")

    @Test
    fun `teams are saved under the key of every project and the key of this one`() {
        val state = teams()
        state.save(team("1"), global = true)
        state.save(team("2"), global = false)
        val saved = state.toPreferences()
        val restored = teams(saved)
        assertEquals(listOf(SavedTeam(team("1"), true), SavedTeam(team("2"), false)), restored.teams.toList())
        assertTrue(PreferenceKeys.AGENT_TEAMS_PREFIX + "/work/project" in saved)
    }

    @Test
    fun `the teams of another project are not this project's`() {
        val state = teams()
        state.save(team("2"), global = false)
        val other = AgentTeams(state.toPreferences(), "/work/other")
        assertEquals(emptyList(), other.teams.toList())
    }

    @Test
    fun `saving a team of a known id replaces it in place`() {
        val state = teams()
        state.save(team("1"), true)
        state.save(team("2"), true)
        state.save(team("1", "Renamed"), false)
        assertEquals(listOf("Renamed", "Team 2"), state.teams.map { it.team.name })
        assertEquals(listOf(false, true), state.teams.map { it.global })
    }

    @Test
    fun `removing a team forgets it`() {
        val state = teams()
        state.save(team("1"), true)
        state.save(team("2"), true)
        state.remove("1")
        assertEquals(listOf("2"), state.teams.map { it.team.id })
    }

    @Test
    fun `a copy has new ids, the name with copy, the same scope, and follows the original`() {
        val state = teams()
        state.save(team("1"), false)
        state.save(team("2"), true)
        val copy = state.duplicate("1")
        assertEquals(listOf("Team 1", "Team 1 copy", "Team 2"), state.teams.map { it.team.name })
        assertEquals(false, copy?.global)
        val original = state.find("1")?.team
        val duplicate = copy?.team
        assertNotEquals(original?.id, duplicate?.id)
        assertNotEquals(original?.manager?.id, duplicate?.manager?.id)
        assertNotEquals(original?.members?.map { it.id }, duplicate?.members?.map { it.id })
        assertEquals(original?.manager?.copy(id = ""), duplicate?.manager?.copy(id = ""))
        assertNull(state.duplicate("missing"))
    }

    @Test
    fun `damaged saved teams are ignored`() {
        val saved =
            mapOf(
                PreferenceKeys.AGENT_TEAMS to "not json at all",
                PreferenceKeys.AGENT_TEAMS_PREFIX + "/work/project" to "[1, {\"id\": 3}]",
            )
        assertEquals(emptyList(), teams(saved).teams.toList())
    }

    @Test
    fun `a team needs a name, named agents with different names, and a member`() {
        val manager = agent("m", "Lead")
        val member = agent("a", "Coder")
        assertNull(teamProblem("Team", manager, listOf(member)))
        assertEquals("Give the team a name", teamProblem("  ", manager, listOf(member)))
        assertEquals("Give every agent a name", teamProblem("Team", manager, listOf(member.copy(name = " "))))
        assertEquals("Give every agent a name", teamProblem("Team", manager.copy(name = ""), listOf(member)))
        assertEquals("Two agents are named \"Lead\"", teamProblem("Team", manager, listOf(member.copy(name = "Lead "))))
        assertEquals("Add at least one member", teamProblem("Team", manager, emptyList()))
    }
}
