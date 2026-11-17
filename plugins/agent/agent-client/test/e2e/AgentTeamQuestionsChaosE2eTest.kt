package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.time.Duration.Companion.minutes

private const val RESTARTED = "The server restarted: the team stopped, the tasks of its members are cancelled."

/**
 * The questions of a member when the network or the server fails: a question waits for the user as long as it takes,
 * an answer is applied once whatever happens to the link, and a restarted server leaves no question waiting.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamQuestionsChaosE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")
    private val team = commandTeam("Crew", store, "Spec", asking = setOf("Spec"))

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.teamConversation(): Pair<E2eApp, Conversation> {
        val preferences = InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + globalTeams(team))
        val app = launchWithProject(project, preferences, chaos = true)
        val conversation = startConversation(app)
        pickTeam(team.id)
        return app to conversation
    }

    private fun Conversation.reportOfSpec(): String = delegationOf("Spec").result

    @Test
    fun `a question waits while the network is down, and its answer reaches the member once the link is back`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            sendPrompt("delegate Spec: ask Which database?=SQLite,Postgres")
            val asked = awaitQuestions(conversation, "Spec", 1).single()
            app.network.goDown()
            awaitReconnecting()
            app.network.comeBack()
            awaitReconnected()
            // Still there, still waiting: nothing answered it for the user.
            awaitQuestions(conversation, "Spec", 1)
            answerChoice("Spec", asked, "SQLite")
            awaitFinalAnswer(conversation)
            assertContains(conversation.reportOfSpec(), "Answer: SQLite")
        }

    @Test
    fun `an answer whose way back was cut is applied once`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            sendPrompt("delegate Spec: ask Which database?=SQLite,Postgres")
            val asked = awaitQuestions(conversation, "Spec", 1).single()
            app.network.dropFromServer = true
            answerChoice("Spec", asked, "Postgres")
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected(AGENT_TIMEOUT)
            awaitFinalAnswer(conversation)
            val task = conversation.delegationOf("Spec")
            awaitDelegationStatus(task, "done")
            assertEquals(1, Regex("Answer: Postgres").findAll(task.result).count(), task.result)
            assertEquals(1, conversation.delegations().size)
        }

    @Test
    fun `a link that goes silent while a question waits is noticed, and the answer then goes through`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            sendPrompt("delegate Spec: ask Which database?=SQLite,Postgres")
            val asked = awaitQuestions(conversation, "Spec", 1).single()
            app.network.blackHole = true
            awaitReconnecting()
            app.network.blackHole = false
            app.network.cutAll()
            awaitReconnected(AGENT_TIMEOUT)
            answerChoice("Spec", asked, "SQLite")
            awaitFinalAnswer(conversation)
            assertContains(conversation.reportOfSpec(), "Answer: SQLite")
        }

    @Test
    fun `on a slow link the questions come in order and an answer in words arrives whole`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            app.network.latencyMs = 300
            sendPrompt("delegate Spec: ask First?=a,b ~ Second?")
            val asked = awaitQuestions(conversation, "Spec", 2)
            assertEquals(listOf("First?", "Second?"), asked.map { it.title })
            answerChoice("Spec", asked.first(), "b")
            answerWords("Spec", asked.last(), "a long answer, in words")
            awaitFinalAnswer(conversation)
            app.network.latencyMs = 0
            assertContains(conversation.reportOfSpec(), "Answer: b")
            assertContains(conversation.reportOfSpec(), "Answer: a long answer, in words")
        }

    @Test
    fun `a server that restarts while a question waits cancels the task, says so, and the next prompt runs`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            sendPrompt("delegate Spec: ask Which database?=SQLite,Postgres")
            awaitQuestions(conversation, "Spec", 1)
            val task = conversation.delegationOf("Spec")
            app.process.restart()
            awaitReconnected(AGENT_TIMEOUT)
            awaitIdle(conversation)
            awaitDelegationStatus(task, "cancelled")
            assertContains(conversation.itemsOf<AgentItem.Notice>().map { it.text }, RESTARTED)
            assertFalse(conversation.delegations().any { it.status == DelegationStatus.Running })
            // No card of the member keeps waiting for an answer nobody listens to.
            awaitCondition(AGENT_TIMEOUT) { conversation.pendingQuestions("Spec").isEmpty() }
            sendPrompt("delegate Spec: ask Again?=yes")
            val again = awaitQuestions(conversation, "Spec", 1).single()
            answerChoice("Spec", again, "yes")
            awaitFinalAnswer(conversation)
            assertContains(conversation.reportOfSpec(), "Answer: yes")
        }
}
