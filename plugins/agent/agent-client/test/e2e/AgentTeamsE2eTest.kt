package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.AgentTeam
import org.example.kotlineditor.state.TeamMember
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Team button of the conversation list makes teams of agents (a manager and its sub-agents, each with a model, skills
 * and a system prompt); a conversation chooses one before its first prompt, and its manager is briefed with it.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamsE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences() = agentPreferences("--store", "$store")

    @Test
    fun `a first use has the default team, and a conversation chooses it`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            val agent = app.project.agent
            val team = agent.teams.single()
            assertEquals("Default team", team.name)
            assertNull(conversation.team)
            awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeam("Default team")).performClick()
            awaitCondition { conversation.team == "Default team" }
            sendPrompt("hello")
            awaitShown(conversation, "manager of the team")
            val shown = shownConversation(conversation)
            assertTrue("manager of the team" in shown, shown)
            assertTrue("Sonnet 5.5" in shown, shown)
            assertTrue("hello" in shown, shown)
            awaitIdle(conversation)
            // Started and idle: the team can still change, the next prompt carries the new briefing.
            agent.chooseTeam(conversation, null)
            assertNull(conversation.team)
        }

    @Test
    fun `a team is made with a manager and a sub-agent, then chosen with the model of its manager`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            val agent = app.project.agent
            awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Duo")
            awaitTag(AgentTags.AGENT_MEMBER_NAME).performTextReplacement("Lead")
            chooseInSelect(AgentTags.AGENT_MEMBER_MODEL, "Smart")
            awaitTag(AgentTags.AGENT_MEMBER_PROMPT).performTextInput("Delegate every coding task to Coder.")
            awaitTag(AgentTags.AGENT_TEAM_ADD_MEMBER).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_NAME).performTextInput("Coder")
            // The fake agent offers no `run` or `simplify` command: they are listed as used by the default team.
            openSelect(AgentTags.AGENT_MEMBER_SKILLS)
            awaitTag("${AgentTags.AGENT_MEMBER_SKILLS}.choice.run", timeout = AGENT_TIMEOUT).performScrollTo().performClick()
            awaitTag("${AgentTags.AGENT_MEMBER_SKILLS}.choice.simplify").performScrollTo().performClick()
            escapeSelect(AgentTags.AGENT_MEMBER_SKILLS)
            awaitTag(AgentTags.AGENT_MEMBER_PROMPT).performTextInput("Write the code.")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Duo" } }
            val duo = assertNotNull(agent.teams.firstOrNull { it.name == "Duo" })
            assertEquals(TeamMember("Lead", "Smart", prompt = "Delegate every coding task to Coder."), duo.manager)
            assertEquals(listOf(TeamMember("Coder", skills = listOf("run", "simplify"), prompt = "Write the code.")), duo.members)
            awaitTag(AgentTags.AGENT_TEAM_CLOSE).performClick()
            awaitTag(AgentTags.AGENT_TEAM).performClick()
            awaitTag(AgentTags.agentTeam("Duo")).performClick()
            awaitModel("Smart")
            sendPrompt("build it")
            awaitShown(conversation, "Delegate every coding task to Coder.")
            awaitShown(conversation, "## Coder (skills: run, simplify)")
        }

    @Test
    fun `a team is deleted and its conversations go on without it`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            val agent = app.project.agent
            agent.chooseTeam(conversation, "Default team")
            awaitCondition { conversation.team == "Default team" }
            awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
            // A first click only asks "Sure?".
            awaitTag(AgentTags.agentTeamDelete("Default team")).performClick()
            assertEquals(1, agent.teams.size)
            awaitTag(AgentTags.agentTeamDelete("Default team")).performClick()
            awaitCondition { agent.teams.isEmpty() }
            assertNull(conversation.team)
        }

    @Test
    fun `a team without a name or a manager name is not saved`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            startConversation(app)
            val agent = app.project.agent
            val before = agent.teams.toList()
            assertEquals(false, agent.saveTeam(null, AgentTeam("", TeamMember("Boss"))))
            assertEquals(false, agent.saveTeam(null, AgentTeam("T", TeamMember(" "))))
            assertEquals(before, agent.teams.toList())
        }

    private fun ComposeUiTest.startWithTeams(): Pair<E2eApp, AgentState> {
        val app = launchWithProject(project, preferences())
        startConversation(app)
        return app to app.project.agent
    }

    // The models the fake agent offers, in the model menu of a new member.
    @Test
    fun `the model menu lists the models of the agent, and a choice is stored by its name`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Solo")
            assertEquals("Default", shownSelect(AgentTags.AGENT_MEMBER_MODEL))
            openSelect(AgentTags.AGENT_MEMBER_MODEL)
            for (name in listOf("Fast", "Smart", "Max")) {
                awaitTag("${AgentTags.AGENT_MEMBER_MODEL}.choice.$name", timeout = AGENT_TIMEOUT)
            }
            awaitTag("${AgentTags.AGENT_MEMBER_MODEL}.choice.Max").performScrollTo().performClick()
            awaitNoTag("${AgentTags.AGENT_MEMBER_MODEL}.filter")
            assertEquals("Max", shownSelect(AgentTags.AGENT_MEMBER_MODEL))
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Solo" } }
            assertEquals("Max", agent.teams.first { it.name == "Solo" }.manager.model)
        }

    @Test
    fun `a model the agent does not offer is typed in the filter and added by its Use row`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Custom")
            openSelect(AgentTags.AGENT_MEMBER_MODEL)
            awaitTag("${AgentTags.AGENT_MEMBER_MODEL}.choice.Fast", timeout = AGENT_TIMEOUT)
            // A name that an offered model has gets no Use row.
            awaitTag("${AgentTags.AGENT_MEMBER_MODEL}.filter").performTextReplacement("Fast")
            awaitNoTag("${AgentTags.AGENT_MEMBER_MODEL}.custom")
            useTyped(AgentTags.AGENT_MEMBER_MODEL, "Opus 9")
            awaitNoTag("${AgentTags.AGENT_MEMBER_MODEL}.filter")
            assertEquals("Opus 9", shownSelect(AgentTags.AGENT_MEMBER_MODEL))
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Custom" } }
            assertEquals("Opus 9", agent.teams.first { it.name == "Custom" }.manager.model)
        }

    @Test
    fun `the effort menu lists the efforts the agent offers, and Default clears the value`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            // The efforts of a model come with an open conversation on it (Fast has none).
            chooseModel("fake-smart")
            awaitEffort("Default")
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Thinker")
            for (name in listOf("Low", "Medium", "High")) {
                openSelect(AgentTags.AGENT_MEMBER_EFFORT)
                awaitTag("${AgentTags.AGENT_MEMBER_EFFORT}.choice.$name", timeout = AGENT_TIMEOUT)
                escapeSelect(AgentTags.AGENT_MEMBER_EFFORT)
            }
            chooseInSelect(AgentTags.AGENT_MEMBER_EFFORT, "High")
            assertEquals("High", shownSelect(AgentTags.AGENT_MEMBER_EFFORT))
            openSelect(AgentTags.AGENT_MEMBER_EFFORT)
            awaitTag("${AgentTags.AGENT_MEMBER_EFFORT}.default").performScrollTo().performClick()
            awaitNoTag("${AgentTags.AGENT_MEMBER_EFFORT}.filter")
            assertEquals("Default", shownSelect(AgentTags.AGENT_MEMBER_EFFORT))
            chooseInSelect(AgentTags.AGENT_MEMBER_EFFORT, "Medium")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Thinker" } }
            assertEquals("Medium", agent.teams.first { it.name == "Thinker" }.manager.effort)
        }

    @Test
    fun `skills are toggled in the menu, which stays open, and a chip removes one`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Skilled")
            assertEquals("No skill", shownSelect(AgentTags.AGENT_MEMBER_SKILLS))
            openSelect(AgentTags.AGENT_MEMBER_SKILLS)
            val skills = AgentTags.AGENT_MEMBER_SKILLS
            awaitTag("$skills.choice.echo", timeout = AGENT_TIMEOUT).performScrollTo().performClick()
            awaitTag("$skills.choice.plan").performScrollTo().performClick()
            awaitTag(AgentTags.agentMemberSkill("echo"))
            awaitTag(AgentTags.agentMemberSkill("plan"))
            // Still open, to choose another.
            awaitTag("$skills.choice.count").performScrollTo().performClick()
            awaitTag(AgentTags.agentMemberSkill("count"))
            // A second click on a chosen one removes it.
            awaitTag("$skills.choice.count").performScrollTo().performClick()
            awaitNoTag(AgentTags.agentMemberSkill("count"))
            // A skill the agent does not offer and no team uses is typed.
            useTyped(skills, "ship")
            awaitTag(AgentTags.agentMemberSkill("ship"))
            escapeSelect(skills)
            awaitTag(AgentTags.agentMemberSkillRemove("echo")).performClick()
            awaitNoTag(AgentTags.agentMemberSkill("echo"))
            awaitTag(AgentTags.agentMemberSkill("plan"))
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Skilled" } }
            assertEquals(listOf("plan", "ship"), agent.teams.first { it.name == "Skilled" }.manager.skills)
        }

    @Test
    fun `None clears the skills`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Bare")
            openSelect(AgentTags.AGENT_MEMBER_SKILLS)
            awaitTag("${AgentTags.AGENT_MEMBER_SKILLS}.choice.echo", timeout = AGENT_TIMEOUT).performScrollTo().performClick()
            awaitTag("${AgentTags.AGENT_MEMBER_SKILLS}.choice.plan").performScrollTo().performClick()
            awaitTag("${AgentTags.AGENT_MEMBER_SKILLS}.default").performScrollTo().performClick()
            awaitNoTag("${AgentTags.AGENT_MEMBER_SKILLS}.filter")
            assertEquals("No skill", shownSelect(AgentTags.AGENT_MEMBER_SKILLS))
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Bare" } }
            assertEquals(emptyList(), agent.teams.first { it.name == "Bare" }.manager.skills)
        }

    @Test
    fun `Escape closes a menu and not the window`() =
        runComposeUiTest {
            startWithTeams()
            openNewTeam()
            openSelect(AgentTags.AGENT_MEMBER_MODEL)
            escapeSelect(AgentTags.AGENT_MEMBER_MODEL)
            awaitTag(AgentTags.AGENT_TEAM_NAME)
            assertEquals("Default", shownSelect(AgentTags.AGENT_MEMBER_MODEL))
        }

    @Test
    fun `a value saved in a team that the agent does not offer is shown and kept by Save`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            val before = agent.teams.single()
            assertEquals("Opus 5.5", before.manager.model)
            assertEquals("high", before.manager.effort)
            awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeamEdit("Default team")).performClick()
            awaitTag(AgentTags.agentTeamMemberEdit(0)).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_MODEL)
            assertEquals("Opus 5.5", shownSelect(AgentTags.AGENT_MEMBER_MODEL))
            assertEquals("high", shownSelect(AgentTags.AGENT_MEMBER_EFFORT))
            // Listed with the values of the other teams, marked as chosen.
            openSelect(AgentTags.AGENT_MEMBER_MODEL)
            awaitTag("${AgentTags.AGENT_MEMBER_MODEL}.choice.Opus 5.5")
            escapeSelect(AgentTags.AGENT_MEMBER_MODEL)
            // Nothing touched: Save keeps the team as it was.
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NEW)
            assertEquals(listOf(before), agent.teams.toList())
        }

    @Test
    fun `a member is added, edited, and removed only after its confirmation`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Crew")
            awaitTag(AgentTags.AGENT_TEAM_ADD_MEMBER).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_NAME).performTextInput("Helper")
            awaitTag(AgentTags.agentTeamMember(1))
            // The manager cannot be removed.
            awaitNoTag(AgentTags.agentTeamMemberRemove(0))
            // The pencil folds the member editor, and opens it again.
            awaitTag(AgentTags.agentTeamMemberEdit(1)).performClick()
            awaitNoTag(AgentTags.AGENT_MEMBER_NAME)
            awaitTag(AgentTags.agentTeamMemberEdit(1)).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_NAME)
            // A single click only asks.
            awaitTag(AgentTags.agentTeamMemberRemove(1)).performClick()
            awaitTag(AgentTags.agentTeamMember(1))
            awaitTag(AgentTags.agentTeamMemberRemove(1)).performClick()
            awaitNoTag(AgentTags.agentTeamMember(1))
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Crew" } }
            assertEquals(emptyList(), agent.teams.first { it.name == "Crew" }.members)
        }

    @Test
    fun `a team is edited by its pencil and by its row, renamed, and saved`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeamEdit("Default team")).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextReplacement("Renamed")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitTag(AgentTags.agentTeamEntry("Renamed"))
            assertEquals(listOf("Renamed"), agent.teams.map { it.name })
            awaitTag(AgentTags.agentTeamEntry("Renamed")).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextReplacement("Again")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitTag(AgentTags.agentTeamEntry("Again"))
            assertEquals(listOf("Again"), agent.teams.map { it.name })
        }

    @Test
    fun `the last team deleted shows the empty state, which creates a team`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
            clickTwice(AgentTags.agentTeamDelete("Default team"))
            awaitTag(AgentTags.AGENT_TEAMS_EMPTY)
            assertTrue(agent.teams.isEmpty())
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Fresh")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitTag(AgentTags.agentTeamEntry("Fresh"))
            assertEquals(listOf("Fresh"), agent.teams.map { it.name })
        }

    @Test
    fun `Cancel with changes needs two clicks and discards them, without changes it is one`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            val before = agent.teams.toList()
            awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeamEdit("Default team")).performClick()
            awaitTag(AgentTags.AGENT_TEAM_CANCEL).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NEW)
            awaitTag(AgentTags.agentTeamEdit("Default team")).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextReplacement("Changed")
            awaitTag(AgentTags.AGENT_TEAM_CANCEL).performClick()
            // Asked "Discard?": still in the editor.
            awaitTag(AgentTags.AGENT_TEAM_CANCEL)
            assertEquals("Discard?", textOf(AgentTags.AGENT_TEAM_CANCEL))
            awaitTag(AgentTags.AGENT_TEAM_NAME)
            awaitTag(AgentTags.AGENT_TEAM_CANCEL).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NEW)
            assertEquals(before, agent.teams.toList())
        }

    @Test
    fun `Save is disabled without a team name or a manager name`() =
        runComposeUiTest {
            val (_, agent) = startWithTeams()
            val before = agent.teams.toList()
            openNewTeam()
            awaitTag(AgentTags.AGENT_TEAM_SAVE).assertIsNotEnabled()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Named")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).assertIsEnabled()
            awaitTag(AgentTags.AGENT_MEMBER_NAME).performTextReplacement("")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).assertIsNotEnabled()
            awaitTag(AgentTags.AGENT_MEMBER_NAME).performTextInput("Boss")
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextReplacement("  ")
            awaitTag(AgentTags.AGENT_TEAM_SAVE).assertIsNotEnabled()
            assertEquals(before, agent.teams.toList())
        }

    @Test
    fun `Enter and Space on a focused select open its menu`() =
        runComposeUiTest {
            startWithTeams()
            openNewTeam()
            val model = AgentTags.AGENT_MEMBER_MODEL
            for (key in listOf(Key.Enter, Key.Spacebar)) {
                val trigger = awaitTag(model)
                trigger.requestFocus()
                trigger.performKeyInput { pressKey(key) }
                awaitTag("$model.filter")
                escapeSelect(model)
            }
        }
}
