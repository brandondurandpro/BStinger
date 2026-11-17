package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import kotlinx.coroutines.runBlocking
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.state.InMemoryPreferenceStore
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val MEMBERS = 8
private const val QUESTIONS = 6
private val NAMES = List(MEMBERS) { "Member$it" }

// Measured here: 450 ms to show 48 questions of 8 members, 4.3 s to answer them all through the UI. The budgets leave
// room for a slow runner, not for a cost per card or a poll per question.
private const val SHOW_BUDGET_MS = 10_000L
private const val ANSWER_BUDGET_MS = 30_000L
private const val BUDGET_MB = 800L

/**
 * Many members asking at once: [MEMBERS] members with [QUESTIONS] questions each wait for the user together. The
 * cards show without a poll per member piling up, each answer reaches its own member, and every task ends.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamQuestionsPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun asks(): String {
        val questions = (0 until QUESTIONS).joinToString(" ~ ") { "Question $it?=yes,no" }
        return "delegate " + NAMES.joinToString(" | ") { "$it: ask $questions" }
    }

    @Test
    fun `members asking dozens of questions at once show them and are answered fast`() =
        runComposeUiTest(testTimeout = 10.minutes) {
            val team = commandTeam("Crew", store, *NAMES.toTypedArray(), asking = NAMES.toSet(), separate = true)
            val preferences = InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + globalTeams(team))
            val app = launchWithProject(project, preferences)
            val conversation = startConversation(app)
            pickTeam(team.id)
            // The agent processes start outside the measure.
            sendPrompt("delegate " + NAMES.joinToString(" | ") { "$it: echo ready" })
            awaitRuns(conversation, 1, ANSWER_BUDGET_MS)
            measure("${MEMBERS * QUESTIONS} questions of $MEMBERS members shown", SHOW_BUDGET_MS, BUDGET_MB) {
                sendPrompt(asks())
                awaitCondition(SHOW_BUDGET_MS) { NAMES.all { conversation.pendingQuestions(it).size == QUESTIONS } }
                renderFrame()
            }
            // One poll per member at work, never one per question: the transcripts followed are those of the members.
            assertEquals(MEMBERS, conversation.members.transcripts.size)
            // The cards are rows of the conversation's list: only the ones at its end are composed.
            runBlocking { conversation.listState.scrollToItem(Int.MAX_VALUE) }
            renderFrame()
            val composed = onAllNodes(hasTestTagPrefix("agent.request.title."), useUnmergedTree = true).fetchSemanticsNodes().size
            assertTrue(composed in 1 until MEMBERS * QUESTIONS, "$composed question cards composed: the list must be virtualized")
            val waiting = NAMES.flatMap { name -> conversation.pendingQuestions(name).map { name to it } }
            measure("${MEMBERS * QUESTIONS} questions answered", ANSWER_BUDGET_MS, BUDGET_MB) {
                // From the last card to the first: the one at the end of the list is always composed.
                for ((name, question) in waiting.asReversed()) {
                    runBlocking { conversation.listState.scrollToItem(Int.MAX_VALUE) }
                    answerChoice(name, question, "yes")
                }
                awaitCondition(ANSWER_BUDGET_MS) { conversation.delegations().all { it.status != DelegationStatus.Running } }
                awaitIdle(conversation, ANSWER_BUDGET_MS)
            }
            assertTrue(conversation.delegations().takeLast(MEMBERS).all { it.status == DelegationStatus.Done })
        }
}
