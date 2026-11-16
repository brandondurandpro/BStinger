package org.example.kotlineditor.state

import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ready-made team: its members, and the Haiku model found among what a provider offers. */
class StarterTeamTest {
    private val provider = AgentCommand(emptyList(), registry = "claude-acp")

    private fun models(vararg values: AgentOptionValue) = AgentOption("model", "Model", AgentOption.MODEL, values.first().value, values.toList())

    @Test
    fun `the team has a manager and the five members, each with a prompt`() {
        val team = starterTeam(provider)
        assertEquals("Manager", team.manager.name)
        assertEquals(listOf("Dev", "Designer", "Tester", "Code reviewer", "Spec writer"), team.members.map { it.name })
        val agents = listOf(team.manager) + team.members
        assertEquals(emptyList(), agents.filter { it.systemPrompt.isBlank() || it.description.isBlank() })
        assertEquals(agents.size, agents.map { it.id }.toSet().size)
        assertNull(teamProblem(team.name, team.manager, team.members))
    }

    @Test
    fun `the Haiku model is chosen by its name or its value`() {
        val byName = models(AgentOptionValue("default", "Default"), AgentOptionValue("m3", "Haiku 5.5"))
        assertEquals(mapOf("model" to "m3"), starterSettings(listOf(byName)))
        val byValue = models(AgentOptionValue("opus", "Big"), AgentOptionValue("claude-haiku-5-5", "Small"))
        assertEquals(mapOf("model" to "claude-haiku-5-5"), starterSettings(listOf(byValue)))
    }

    @Test
    fun `a provider without a Haiku model, or without models, keeps its default`() {
        val others = models(AgentOptionValue("smart", "Smart"), AgentOptionValue("fast", "Fast"))
        assertEquals(emptyMap(), starterSettings(listOf(others)))
        assertEquals(emptyMap(), starterSettings(emptyList()))
        val effort = AgentOption("effort", "Effort", AgentOption.THOUGHT_LEVEL, "high", listOf(AgentOptionValue("high", "Haiku")))
        assertEquals(emptyMap(), starterSettings(listOf(effort)))
    }

    @Test
    fun `every agent of the team gets the settings`() {
        val team = starterTeam(provider, mapOf("model" to "m3"))
        val agents = listOf(team.manager) + team.members
        assertEquals(setOf(mapOf("model" to "m3")), agents.map { it.settings }.toSet())
    }
}
