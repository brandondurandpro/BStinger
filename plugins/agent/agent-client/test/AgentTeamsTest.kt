package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentOptionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentTeamsTest {
    @Test
    fun `teams survive their encoding with lines, tabs and skills`() {
        val manager = TeamMember("Boss", "Opus 5.5", "high", prompt = "Delegate:\n\tto Dev \\ always")
        val dev = TeamMember("Dev", "Sonnet 5.5", skills = listOf("simplify", "run"), prompt = "Code")
        val teams = listOf(AgentTeam("A", manager, listOf(dev)), AgentTeam("B", TeamMember("Solo")))
        assertEquals(teams, decodeTeams(encodeTeams(teams)))
    }

    @Test
    fun `a damaged line, a team without manager or a nameless member is ignored`() {
        val saved = "nonsense\nA\tmember\tDev\t\t\t\tx\n\tmanager\tNobody\t\t\t\t\nB\tmanager\t\t\t\t\t\nC\tmanager\tBoss\t\t\t\t"
        assertEquals(listOf(AgentTeam("C", TeamMember("Boss"))), decodeTeams(saved))
    }

    @Test
    fun `the default team has an Opus manager, a Sonnet developer, an Opus designer and a tester`() {
        val team = defaultTeams().single()
        assertEquals("Opus 5.5", team.manager.model)
        assertEquals("high", team.manager.effort)
        val models = team.members.associate { it.name to it.model }
        assertEquals("Sonnet 5.5", models["Dev"])
        assertEquals("Opus 5.5", models["Designer"])
        assertTrue(team.members.any { it.name == "Tester" })
        assertTrue(team.members.all { it.prompt.isNotBlank() })
    }

    @Test
    fun `the briefing gives the manager its prompt, each member's prompt and then the request`() {
        val team = AgentTeam("T", TeamMember("Boss", prompt = "Coordinate."), listOf(TeamMember("Dev", "Sonnet", skills = listOf("run"), prompt = "Code.")))
        val briefing = teamBriefing(team, "Fix the bug")
        assertTrue(briefing.startsWith("You are Boss, the manager of the team \"T\"."))
        assertTrue("Coordinate." in briefing)
        assertTrue("## Dev (Sonnet, skills: run)\nCode." in briefing)
        assertTrue(briefing.endsWith("# Request\nFix the bug"))
    }

    @Test
    fun `a value is found by the words of the user, letters and digits only`() {
        val values = listOf("fake-fast" to "Fast", "fake-smart" to "Smart", "opus-5-5" to "Opus 5.5")
        assertEquals("opus-5-5", matchingValue(values, "opus 5.5"))
        assertEquals("fake-smart", matchingValue(values, "smart"))
        assertEquals("fake-smart", matchingValue(values, "fake-smart"))
        assertNull(matchingValue(values, "haiku"))
        assertNull(matchingValue(values, " "))
    }

    // A saved effort "high" is a value of its own: an agent that offers only "xhigh" and "low" does not offer it.
    private val efforts = listOf(AgentOptionValue("xhigh", "xhigh", null), AgentOptionValue("low", "low", null))

    @Test
    fun `a saved effort the agent does not offer gets no check mark on a longer one`() {
        assertNull(offeredRow(efforts, "high", loose = true))
    }

    @Test
    fun `a saved effort the agent does not offer is listed with the values used in the teams`() {
        assertEquals(listOf("high"), otherValues(efforts, listOf("high"), loose = true))
    }
}
