package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamText
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.toPreferences
import org.example.kotlineditor.state.workOf
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val ASKS = "ask Which database?=SQLite,Postgres ~ Why?"

/**
 * A member allowed to ask the user questions: its questions show in the conversation of its team, named after it,
 * with the choices it proposes and a field for the user's own words; the member goes on with the answers; one that
 * may not ask never does. Stop cancels them, and the setting is in the team form.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamQuestionsE2eTest {
    private val project = SampleProject.standard().apply { commitAll() }
    private val store = Files.createTempDirectory("agent-store")
    private val servers = TeamRegistry()

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
        servers.close()
    }

    private fun preferences(vararg more: Pair<String, String>) =
        InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + more)

    private fun crew(vararg members: String, asking: Set<String> = members.toSet(), separate: Boolean = false): Team =
        commandTeam("Crew", store, *members, asking = asking, separate = separate)

    private fun ComposeUiTest.teamConversation(team: Team): Pair<E2eApp, Conversation> {
        val app = launchWithProject(project, preferences(globalTeams(team)))
        val conversation = startConversation(app)
        pickTeam(team.id)
        return app to conversation
    }

    private fun ComposeUiTest.isGone(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    @Test
    fun `a member asks its questions, the user answers by a choice and in words, and the member goes on`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            val (_, conversation) = teamConversation(crew("Spec"))
            sendPrompt("delegate Spec: $ASKS")
            val asked = awaitQuestions(conversation, "Spec", 2)
            assertEquals(listOf("Which database?", "Why?"), asked.map { it.title })
            // Named after the member, with the choices as buttons, and a field for words.
            assertEquals("Spec asks: Which database?", textOf(AgentTags.agentRequestTitle("Spec", asked.first().id)))
            awaitTag(AgentTags.agentQuestionChoice("Spec", asked.first().id, "Postgres"))
            awaitTag(AgentTags.agentQuestionText("Spec", asked.last().id))
            // The task is still running while the user thinks, and no plumbing shows.
            awaitDelegationStatus(conversation.delegationOf("Spec"), "running…")
            assertFalse(TeamText.ASK_OPENING in shownConversation(conversation))
            answerChoice("Spec", asked.first(), "SQLite")
            answerWords("Spec", asked.last(), "it is small")
            awaitFinalAnswer(conversation)
            val task = conversation.delegationOf("Spec")
            awaitDelegationStatus(task, "done")
            assertContains(task.result, "Answer: SQLite")
            assertContains(task.result, "Answer: it is small")
            assertFalse(TeamText.ASK_OPENING in task.result)
            // Asking changes nothing in the project.
            before.assertUnchanged(project)
        }

    @Test
    fun `the first question shows what the member wrote before asking, the proposals the user decides on`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Designer"))
            sendPrompt("delegate Designer: $ASKS")
            val asked = awaitQuestions(conversation, "Designer", 2)
            val said = AgentTags.agentRequestSaid("Designer", asked.first().id)
            awaitTag(said)
            assertContains(textOf(said), "I have questions.")
            assertFalse(TeamText.ASK_OPENING in textOf(said))
            // The second question of the same message does not repeat it.
            assertTrue(isGone(AgentTags.agentRequestSaid("Designer", asked.last().id)))
            answerChoice("Designer", asked.first(), "SQLite")
            answerWords("Designer", asked.last(), "small")
            awaitFinalAnswer(conversation)
            assertTrue(isGone(said), "an answered question no longer shows it")
        }

    @Test
    fun `an answer is applied once, and the cards then say what was answered`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Spec"))
            sendPrompt("delegate Spec: ask Which database?=SQLite,Postgres")
            val asked = awaitQuestions(conversation, "Spec", 1).single()
            answerChoice("Spec", asked, "Postgres")
            awaitFinalAnswer(conversation)
            val task = conversation.delegationOf("Spec")
            // Its work shows the question answered, and the user's answer as the member's next message.
            awaitTag(AgentTags.agentDelegationToggle(task.id)).performClick()
            awaitTag(AgentTags.agentDelegationWork(task.id), timeout = AGENT_TIMEOUT)
            awaitCondition(AGENT_TIMEOUT) { conversation.workOf(task).any { it is AgentItem.Permission && it.chosen == "Postgres" } }
            val work = conversation.workOf(task)
            assertEquals(1, work.count { it is AgentItem.Permission && it.question })
            assertEquals(1, conversation.delegations().size)
        }

    @Test
    fun `the answer cannot be sent empty`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Spec"))
            sendPrompt("delegate Spec: ask Why?")
            val asked = awaitQuestions(conversation, "Spec", 1).single()
            awaitTag(AgentTags.agentQuestionSend("Spec", asked.id)).assertIsNotEnabled()
            awaitTag(AgentTags.agentQuestionText("Spec", asked.id)).performTextInput("   ")
            awaitTag(AgentTags.agentQuestionSend("Spec", asked.id)).assertIsNotEnabled()
            awaitTag(AgentTags.agentQuestionText("Spec", asked.id)).performTextInput("because")
            awaitTag(AgentTags.agentQuestionSend("Spec", asked.id)).assertIsEnabled().performClick()
            awaitFinalAnswer(conversation)
            assertContains(conversation.delegationOf("Spec").result, "Answer: because")
        }

    @Test
    fun `a member that may not ask goes on without questions`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Quiet", "Spec", asking = setOf("Spec")))
            sendPrompt("delegate Quiet: $ASKS")
            awaitFinalAnswer(conversation)
            val task = conversation.delegationOf("Quiet")
            awaitDelegationStatus(task, "done")
            assertEquals("I have questions.", task.result.trim())
            assertTrue(conversation.items.none { it is AgentItem.Permission })
        }

    @Test
    fun `two members ask at once, each card names its member and is answered apart`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Spec", "Designer", separate = true))
            sendPrompt("delegate Spec: ask Which database?=SQLite,Postgres | Designer: ask Several designs?=yes,no")
            val spec = awaitQuestions(conversation, "Spec", 1).single()
            val designer = awaitQuestions(conversation, "Designer", 1).single()
            assertEquals("Spec asks: Which database?", textOf(AgentTags.agentRequestTitle("Spec", spec.id)))
            assertEquals("Designer asks: Several designs?", textOf(AgentTags.agentRequestTitle("Designer", designer.id)))
            // Answering one leaves the other waiting.
            answerChoice("Designer", designer, "yes")
            awaitCondition(AGENT_TIMEOUT) { conversation.delegationOf("Designer").status == DelegationStatus.Done }
            assertEquals(DelegationStatus.Running, conversation.delegationOf("Spec").status)
            answerChoice("Spec", spec, "SQLite")
            awaitFinalAnswer(conversation)
            assertContains(conversation.delegationOf("Designer").result, "Answer: yes")
            assertContains(conversation.delegationOf("Spec").result, "Answer: SQLite")
        }

    @Test
    fun `a question can be answered from the tab of its member`() =
        runComposeUiTest {
            val team = crew("Spec")
            val (_, conversation) = teamConversation(team)
            sendPrompt("delegate Spec: ask Which database?=SQLite,Postgres")
            val asked = awaitQuestions(conversation, "Spec", 1).single()
            awaitTag(AgentTags.agentMemberTab(team.members.first().id)).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_TRANSCRIPT, timeout = AGENT_TIMEOUT)
            answerChoice("Spec", asked, "SQLite")
            awaitCondition(AGENT_TIMEOUT) { conversation.delegationOf("Spec").status == DelegationStatus.Done }
            assertContains(conversation.delegationOf("Spec").result, "Answer: SQLite")
            // The tab shows what the member asked as cards, and no block.
            val transcript = checkNotNull(conversation.members.transcripts.values.firstOrNull() ?: conversation.members.transcripts[conversation.delegationOf("Spec").conversation])
            assertTrue(transcript.items.none { it is AgentItem.AgentText && TeamText.ASK_OPENING in it.text })
        }

    @Test
    fun `Stop cancels the questions waiting, the task and the run, and the next prompt runs`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Spec"))
            sendPrompt("delegate Spec: $ASKS")
            val asked = awaitQuestions(conversation, "Spec", 2)
            awaitTag(AgentTags.AGENT_STOP).performClick()
            awaitIdle(conversation)
            val task = conversation.delegationOf("Spec")
            awaitDelegationStatus(task, "cancelled")
            awaitCondition(AGENT_TIMEOUT) { conversation.members.transcripts.isEmpty() }
            for (question in asked) awaitNoTag(AgentTags.agentQuestionText("Spec", question.id))
            assertEquals(listOf("Stopped."), conversation.itemsOf<AgentItem.Notice>().map { it.text })
            sendPrompt("delegate Spec: ask Again?=yes")
            val again = awaitQuestions(conversation, "Spec", 1).single()
            answerChoice("Spec", again, "yes")
            awaitCondition(AGENT_TIMEOUT) { conversation.delegationOf("Spec").status == DelegationStatus.Done }
        }

    @Test
    fun `the team form says which members may ask, and the manager has no such setting`() =
        runComposeUiTest {
            val team = crew("Spec", "Dev", asking = setOf("Spec"))
            val app = launchWithProject(project, preferences(globalTeams(team)), agents = servers.settings())
            startConversation(app)
            openTeams()
            awaitTag(AgentTags.agentTeam(team.id)).performClick()
            awaitTag(AgentTags.agentTeamField(AgentTags.TEAM_MANAGER, "name"))
            assertTrue(isGone(AgentTags.agentTeamField(AgentTags.TEAM_MANAGER, "ask")))
            // Spec was allowed, Dev was not: allow Dev too, and it is saved with the team.
            awaitTag(AgentTags.agentTeamTab(AgentTags.teamMember(1))).performClick()
            awaitTag(AgentTags.agentTeamField(AgentTags.teamMember(1), "ask")).performScrollTo().performClick()
            saveTeam()
            awaitCondition { app.project.agent.teams.teams.firstOrNull()?.team?.members?.all { it.canAsk } == true }
            val saved = app.project.agent.toPreferences().getValue(PreferenceKeys.AGENT_TEAMS)
            assertContains(saved, "\"canAsk\":true")
            assertFalse(app.project.agent.teams.teams.first().team.manager.canAsk)
        }
}
