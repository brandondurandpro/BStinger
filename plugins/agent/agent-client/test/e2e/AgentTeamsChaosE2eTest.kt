package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The window of the teams asks the server what the agent offers (its models, efforts, commands) when it opens: with
 * the link down the request waits for the server, so after a few seconds the menus say it does not answer and a typed value still works;
 * once the link is back the same window lists the values, and one opened again does too.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamsChaosE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences() = agentPreferences("--store", "$store")

    @Test
    fun `with the network down a model is still typed and saved, and the menu lists the models once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            startConversation(app)
            val agent = app.project.agent
            app.network.goDown()
            awaitReconnecting()
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Offline")
            val model = AgentTags.AGENT_MEMBER_MODEL
            openSelect(model)
            // The request waits for the server: after a few seconds the menu says the agent does not answer.
            awaitTag("$model.unavailable", timeout = AGENT_TIMEOUT)
            awaitNoTag("$model.choice.Fast")
            useTyped(model, "Typed model")
            awaitNoTag("$model.filter")
            assertEquals("Typed model", shownSelect(model))
            // Back: the answer to the request made while it was down fills the menu of the same window.
            app.network.comeBack()
            awaitReconnected()
            openSelect(model)
            for (name in listOf("Fast", "Smart", "Max")) awaitTag("$model.choice.$name", timeout = AGENT_TIMEOUT)
            awaitNoTag("$model.unavailable")
            awaitNoTag("$model.loading")
            escapeSelect(model)
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Offline" } }
            assertEquals("Typed model", agent.teams.first { it.name == "Offline" }.manager.model)
        }

    @Test
    fun `with the network down the effort and skills menus load, and a skill is still typed`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            startConversation(app)
            app.network.goDown()
            awaitReconnecting()
            openNewTeam()
            val effort = AgentTags.AGENT_MEMBER_EFFORT
            openSelect(effort)
            awaitTag("$effort.unavailable", timeout = AGENT_TIMEOUT)
            escapeSelect(effort)
            val skills = AgentTags.AGENT_MEMBER_SKILLS
            openSelect(skills)
            awaitTag("$skills.unavailable", timeout = AGENT_TIMEOUT)
            useTyped(skills, "ship")
            awaitTag(AgentTags.agentMemberSkill("ship"))
            escapeSelect(skills)
            app.network.comeBack()
            awaitReconnected()
            openSelect(skills)
            awaitTag("$skills.choice.echo", timeout = AGENT_TIMEOUT)
            awaitNoTag("$skills.loading")
        }

    @Test
    fun `a window opened again once the network is back lists the models of the agent`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            startConversation(app)
            val model = AgentTags.AGENT_MEMBER_MODEL
            app.network.goDown()
            awaitReconnecting()
            openNewTeam()
            openSelect(model)
            awaitTag("$model.unavailable", timeout = AGENT_TIMEOUT)
            escapeSelect(model)
            awaitTag(AgentTags.AGENT_TEAM_CANCEL).performClick()
            awaitTag(AgentTags.AGENT_TEAM_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_TEAM_CLOSE)
            app.network.comeBack()
            awaitReconnected()
            openNewTeam()
            openSelect(model)
            for (name in listOf("Fast", "Smart", "Max")) awaitTag("$model.choice.$name", timeout = AGENT_TIMEOUT)
            awaitNoTag("$model.loading")
        }

    @Test
    fun `a server restarted while the window is open does not leave its menu loading`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences(), chaos = true)
            startConversation(app)
            val model = AgentTags.AGENT_MEMBER_MODEL
            openNewTeam()
            app.process.restart()
            awaitReconnected()
            openSelect(model)
            awaitNoTag("$model.loading", timeout = AGENT_TIMEOUT)
        }
}
