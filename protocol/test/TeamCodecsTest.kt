package org.example.kotlineditor.unit

import kotlinx.serialization.json.Json
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.rpc.agentSkills
import org.example.kotlineditor.rpc.skillsToJson
import org.example.kotlineditor.rpc.team
import org.example.kotlineditor.rpc.teamsOfText
import org.example.kotlineditor.rpc.teamsToText
import org.example.kotlineditor.rpc.toJson
import kotlin.test.Test
import kotlin.test.assertEquals

class TeamCodecsTest {
    private fun agent(id: String, name: String) =
        TeamAgent(
            id = id,
            name = name,
            description = "Does $name things\nover two lines",
            agent = AgentCommand(emptyList(), registry = "claude-acp"),
            settings = mapOf("model" to "opus", "effort" to "high"),
            skills = listOf("/p/skills/review", "/home/me/.kotlin-editor/skills/tests"),
            systemPrompt = "Be \"brief\"\tand kind.\n",
        )

    private val team = Team("t1", "Backend", agent("m", "Lead"), listOf(agent("a", "Coder"), agent("b", "Tester")))

    @Test
    fun `a team survives the round trip`() {
        assertEquals(team, Json.parseToJsonElement(team.toJson().toString()).team())
    }

    @Test
    fun `an agent with no setting, no skill and no prompt survives the round trip`() {
        val bare = TeamAgent("x", "Bare", "", AgentCommand(listOf("agent", "--acp"), mapOf("KEY" to "v")))
        val alone = Team("t", "Alone", bare, listOf(bare.copy(id = "y", name = "Other")))
        assertEquals(alone, Json.parseToJsonElement(alone.toJson().toString()).team())
    }

    @Test
    fun `teams survive the text of the preferences`() {
        val other = team.copy(id = "t2", name = "Frontend")
        assertEquals(listOf(team, other), teamsOfText(listOf(team, other).teamsToText()))
    }

    @Test
    fun `no text and no team give no team`() {
        assertEquals(emptyList(), teamsOfText(null))
        assertEquals(emptyList(), teamsOfText(""))
        assertEquals(emptyList(), teamsOfText(emptyList<Team>().teamsToText()))
    }

    @Test
    fun `damaged text gives no team and does not throw`() {
        assertEquals(emptyList(), teamsOfText("not json"))
        assertEquals(emptyList(), teamsOfText("{\"id\": 1}"))
        assertEquals(emptyList(), teamsOfText("[1, 2"))
    }

    @Test
    fun `a damaged team is ignored and the others are kept`() {
        val good = team.toJson().toString()
        val text = "[$good, {\"id\": \"broken\", \"name\": \"x\"}, 7, {\"id\": \"t3\", \"name\": \"n\", \"manager\": {}, \"members\": []}]"
        assertEquals(listOf(team), teamsOfText(text))
    }

    @Test
    fun `skills survive the round trip`() {
        val skills = listOf(AgentSkill("/p/skills/a", "A", "Does a", true), AgentSkill("/d/skills/b", "B", "", false))
        assertEquals(skills, skills.skillsToJson().agentSkills())
    }
}
