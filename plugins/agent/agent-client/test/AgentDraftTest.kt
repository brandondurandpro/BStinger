package org.example.kotlineditor.state

import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.AgentAuthMethod
import org.example.kotlineditor.model.AgentChoices
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.AgentOption
import org.example.kotlineditor.model.AgentOptionValue
import org.example.kotlineditor.model.AgentRegistryEntry
import org.example.kotlineditor.model.AgentSkill
import org.example.kotlineditor.model.AgentSlashCommand
import org.example.kotlineditor.model.TeamAgent
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The form of an agent of a team: what it keeps of its choices while the provider answers, or fails to. */
class AgentDraftTest {
    private val provider = AgentCommand(emptyList(), registry = "fake")

    private fun values(vararg names: String) = names.map { AgentOptionValue(it, it.replaceFirstChar(Char::uppercase)) }

    private fun model(current: String) = AgentOption("model", "Model", AgentOption.MODEL, current, values("fast", "smart", "max"))

    private fun effort(vararg names: String) = AgentOption("effort", "Effort", AgentOption.THOUGHT_LEVEL, "default", values(*names))

    // Offers the models, and the efforts of the model asked for: none for fast, up to high for smart, max too for max.
    private class Provider(var failure: IOException? = null, var silent: Boolean = false) : AgentChoices {
        val asked = ArrayList<Map<String, String>>()

        override suspend fun options(agent: AgentCommand, directory: String, choices: Map<String, String>): List<AgentOption> {
            asked.add(choices)
            failure?.let { throw it }
            if (silent) return emptyList()
            val chosen = choices["model"] ?: "fast"
            val models = AgentOption("model", "Model", AgentOption.MODEL, chosen, listOf("fast", "smart", "max").map { AgentOptionValue(it, it) })
            val names =
                when (chosen) {
                    "smart" -> listOf("default", "low", "high")
                    "max" -> listOf("default", "low", "high", "max")
                    else -> emptyList()
                }
            val efforts = names.map { AgentOptionValue(it, it) }
            val effort = AgentOption("effort", "Effort", AgentOption.THOUGHT_LEVEL, "default", efforts)
            return if (efforts.isEmpty()) listOf(models) else listOf(models, effort)
        }

        override suspend fun skills(root: String): List<AgentSkill> = emptyList()

        override suspend fun registry(): List<AgentRegistryEntry> = emptyList()

        override suspend fun setOption(conversation: String, agent: AgentCommand, option: String, value: String) = Unit

        override suspend fun commands(agent: AgentCommand): List<AgentSlashCommand> = emptyList()

        override suspend fun authMethods(agent: AgentCommand): List<AgentAuthMethod> = emptyList()

        override suspend fun authenticate(agent: AgentCommand, method: String) = Unit

        override suspend fun fork(agent: AgentCommand, session: String, directory: String) = ""
    }

    private fun draft(settings: Map<String, String> = emptyMap()) = AgentDraft(TeamAgent("a", "Agent", "", provider, settings))

    @Test
    fun `the options of the provider are read, and a model that is not the first is asked for`() {
        val agent = draft(mapOf("model" to "smart", "effort" to "high"))
        val server = Provider()
        runBlocking { agent.loadOptions(server, "/work") }
        assertEquals(listOf(emptyMap(), mapOf("model" to "smart")), server.asked)
        assertEquals(listOf("default", "low", "high"), agent.effortOption?.values?.map { it.value })
        assertEquals(mapOf("model" to "smart", "effort" to "high"), agent.settings.toMap())
    }

    @Test
    fun `an effort the model does not offer is forgotten, and so is an unknown model`() {
        val agent = draft(mapOf("model" to "fast", "effort" to "high"))
        runBlocking { agent.loadOptions(Provider(), "/work") }
        assertEquals(mapOf("model" to "fast"), agent.settings.toMap())
        assertNull(agent.effortOption)
        val stale = draft(mapOf("model" to "retired"))
        runBlocking { stale.loadOptions(Provider(), "/work") }
        assertEquals(emptyMap(), stale.settings.toMap())
    }

    @Test
    fun `a provider that cannot answer leaves the choices as they are`() {
        val failing = draft(mapOf("model" to "smart", "effort" to "high"))
        runBlocking { failing.loadOptions(Provider(failure = IOException("not installed")), "/work") }
        assertNull(failing.options)
        assertEquals(mapOf("model" to "smart", "effort" to "high"), failing.settings.toMap())
        val silent = draft(mapOf("model" to "smart"))
        runBlocking { silent.loadOptions(Provider(silent = true), "/work") }
        assertEquals(mapOf("model" to "smart"), silent.settings.toMap())
    }

    @Test
    fun `changing the provider forgets the settings of the previous one`() {
        val agent = draft(mapOf("model" to "smart"))
        runBlocking { agent.loadOptions(Provider(), "/work") }
        agent.useProvider("other")
        assertEquals("other", agent.provider.registry)
        assertEquals(emptyMap(), agent.settings.toMap())
        assertNull(agent.options)
        agent.choose(model("fast"), "smart")
        agent.useProvider("other")
        assertEquals(mapOf("model" to "smart"), agent.settings.toMap())
    }

    @Test
    fun `an effort is chosen and left to the agent again`() {
        val agent = draft()
        agent.choose(effort("default", "low"), "low")
        assertEquals(mapOf("effort" to "low"), agent.settings.toMap())
        agent.choose(effort("default", "low"), null)
        assertEquals(emptyMap(), agent.settings.toMap())
    }
}
