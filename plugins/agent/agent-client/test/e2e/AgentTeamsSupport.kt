package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import org.example.kotlineditor.model.AgentCommand
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamAgent
import org.example.kotlineditor.rpc.teamsToText
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.testkit.acp.FakeRegistry
import org.example.kotlineditor.ui.AgentTags

/** The agent of the teams of the tests: the one the fake registry serves, which offers models and efforts. */
const val TEAM_PROVIDER = "fake-agent"

/** Preferences with agent mode on and the registry agent [TEAM_PROVIDER] as the agent of the settings, plus [more]. */
fun teamPreferences(vararg more: Pair<String, String>) =
    InMemoryPreferenceStore(
        mapOf(
            PreferenceKeys.AGENT_MODE to "true",
            PreferenceKeys.AGENT_REGISTRY to TEAM_PROVIDER,
        ) + more,
    )

/** A team of tests: a manager and [members] members, all on [TEAM_PROVIDER]. */
fun sampleTeam(name: String, members: Int = 1, id: String = "team-$name"): Team {
    fun agent(agentName: String) =
        TeamAgent(
            id = "$id-$agentName",
            name = agentName,
            description = "$agentName of $name",
            agent = AgentCommand(emptyList(), registry = TEAM_PROVIDER),
        )
    return Team(id, name, agent("Lead"), (1..members).map { agent("Member $it") })
}

/** The preferences key and value that hold [teams] for every project. */
fun globalTeams(vararg teams: Team): Pair<String, String> = PreferenceKeys.AGENT_TEAMS to teams.toList().teamsToText()

/** Opens the Teams window from the conversation's bar. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.openTeams() {
    awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
    awaitTag(AgentTags.AGENT_TEAMS_DIALOG)
}

/** Starts the form of a new team named [name]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.newTeam(name: String) {
    awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
    awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput(name)
}

/** The field [field] of the agent of [role], scrolled into the view of the form. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitField(role: String, field: String, timeout: Long = TIMEOUT): SemanticsNodeInteraction =
    awaitTag(AgentTags.agentTeamField(role, field), timeout).performScrollTo()

/** Types [text] in the field [field] of the agent of [role], replacing what it holds. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.fillAgent(role: String, field: String, text: String) {
    awaitField(role, field).performTextReplacement(text)
}

/**
 * Chooses [value] in the menu of the field [field] (`model`, `effort`, `provider`) of the agent of [role], once the
 * server said what it offers.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.chooseInAgent(role: String, field: String, value: String) {
    awaitField(role, field, AGENT_TIMEOUT).performClick()
    awaitTag(AgentTags.agentTeamValue(role, field, value)).performClick()
    awaitNoTag(AgentTags.agentTeamValue(role, field, value))
}

/** Waits until the button [field] of the agent of [role] shows [name] (`Smart`). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitShownChoice(role: String, field: String, name: String) =
    awaitOption(AgentTags.agentTeamField(role, field), name)

/** Saves the form, which closes it. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.saveTeam() {
    awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
    awaitNoTag(AgentTags.AGENT_TEAM_FORM)
}

/** The fake registry and a data directory for a server that reads it. */
class TeamRegistry : AutoCloseable {
    val registry = FakeRegistry(org.example.kotlineditor.acp.registryPlatformForTests())
    val data: java.nio.file.Path = java.nio.file.Files.createTempDirectory("agent-data")

    fun settings() = agentSettings(registry.url, data.toFile())

    override fun close() {
        registry.close()
        data.toFile().deleteRecursively()
    }
}
