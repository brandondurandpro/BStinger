package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationTeamTest {
    private val provider = AgentCommand(emptyList(), registry = "claude-acp")

    private fun agent(id: String) = TeamAgent(id, "Agent $id", "", provider)

    private fun team(id: String) = Team(id, "Team $id", agent("m$id"), listOf(agent("a$id")))

    private fun conversation(session: String?, teamId: String?): Conversation {
        val made = Conversation(1, "Title", "agent/conversation-1")
        made.session = session
        made.teamId = teamId
        return made
    }

    @Test
    fun `a new conversation has no team and is not missing one`() {
        val made = Conversation(1, "Title", "agent/conversation-1")
        assertNull(made.teamId)
        assertNull(made.team)
        assertFalse(made.teamMissing)
    }

    @Test
    fun `the team is resolved by its id, so an edited team shows as edited`() {
        val teams = AgentTeams(emptyMap(), "/work/project")
        teams.save(team("1"), global = true)
        val made = conversation(null, "1")
        made.teamLookup = teams::find
        assertEquals("Team 1", made.team?.team?.name)
        teams.save(team("1").copy(name = "Renamed"), global = true)
        assertEquals("Renamed", made.team?.team?.name)
        assertFalse(made.teamMissing)
    }

    @Test
    fun `a team that is gone is missing`() {
        val teams = AgentTeams(emptyMap(), "/work/project")
        teams.save(team("1"), global = true)
        val made = conversation("s1", "1")
        made.teamLookup = teams::find
        teams.remove("1")
        assertNull(made.team)
        assertTrue(made.teamMissing)
    }

    @Test
    fun `the teams of the conversations that have a session and a team are saved`() {
        val text =
            conversationTeamsText(
                listOf(conversation("s1", "t1"), conversation("s2", null), conversation(null, "t3"), conversation("s4", "t4")),
            )
        assertEquals("s1\tt1\ns4\tt4", text)
    }

    @Test
    fun `what is saved is restored`() {
        val text = conversationTeamsText(listOf(conversation("s1", "t1"), conversation("s2", "t-2")))
        assertEquals(mapOf("s1" to "t1", "s2" to "t-2"), teamsOfSessions(text))
    }

    @Test
    fun `nothing saved restores nothing`() {
        assertEquals(emptyMap(), teamsOfSessions(null))
        assertEquals(emptyMap(), teamsOfSessions(""))
    }

    @Test
    fun `damaged lines are ignored and the others restored`() {
        val text = "no tab here\n\t\n\tonly-team\nonly-session\t\n   \ns1\tt1\n\ns2\tt2\textra\ns1\tt9"
        assertEquals(mapOf("s1" to "t9", "s2" to "t2\textra"), teamsOfSessions(text))
    }
}
