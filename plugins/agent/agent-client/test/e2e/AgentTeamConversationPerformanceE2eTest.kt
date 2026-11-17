package org.example.kotlineditor.e2e

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.memberKey
import org.example.kotlineditor.state.workOf
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

// A conversation of many tasks: runs of ROUNDS rounds, each one a task for every member.
private val MEMBERS = listOf("Backend", "Tester", "Docs", "Ops")
private const val ROUNDS = 10
private const val RUNS = 5
private const val DELEGATIONS = RUNS * ROUNDS * 4

// The work of one task: a message and a tool call per step, two rows each.
private const val WORK_STEPS = 1_500
private const val WORK_ROWS = 2 * WORK_STEPS
private const val STEPS = 40
private const val WHEEL_PIXELS = 90f

// Measured here: 200 ms for a run of 40 delegations, 720 ms to follow a task of 3,000 rows, 430 ms to expand its
// card, 200 ms to expand a card among 200 and to open the tab of a member; scrolling 40 frames: 380 to 910 ms by the
// wheel, 610 to 1,120 ms through the list, 660 to 920 ms jumping. The budgets leave room for a slow runner, not for
// a cost per card or per row.
private const val RUN_BUDGET_MS = 15_000L
private const val EXPAND_BUDGET_MS = 5_000L
private const val SCROLL_BUDGET_MS = 4_000L
private const val JUMPS_BUDGET_MS = 3_000L
private const val WORK_BUDGET_MS = 15_000L
private const val TAB_BUDGET_MS = 5_000L
private const val BUDGET_MB = 800L
private const val MAX_COMPOSED_CARDS = 60
private const val MAX_COMPOSED_WORK_ROWS = 40
private const val MAX_COMPOSED_TAB_ROWS = 80
private const val CARDS = "agent.delegation.toggle."
private const val WORK_ROW = "agent.member.row."

/**
 * A team conversation on large inputs: [DELEGATIONS] tasks delegated, and a task whose member did [WORK_ROWS] rows of
 * work, shown in its card and in the tab of the member, within budgets that a cost per card or per row breaks. The
 * cards and the work are virtualized, and a finished task holds neither a poll nor a transcript.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamConversationPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.teamConversation(team: Team): Conversation {
        val preferences = InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + globalTeams(team))
        val app = launchWithProject(project, preferences)
        val conversation = startConversation(app)
        pickTeam(team.id)
        return conversation
    }

    // A run of ROUNDS rounds, each member echoing in each.
    private fun runOf(run: Int): String {
        val rounds =
            List(ROUNDS) { round ->
                MEMBERS.joinToString(" | ") { member -> "$member: echo $member-$run-$round" }
            }
        return "delegate " + rounds.joinToString(" ;; ")
    }

    // The net cost of a wheel in progress, then of jumps, over the list of [state], which holds [rows] rows.
    private fun ComposeUiTest.scrolls(what: String, state: LazyListState, rows: Int) {
        runBlocking { state.scrollToItem(0) }
        renderFrame()
        val still = costOf { repeat(STEPS) { renderFrame() } }
        val wheeled =
            costOf {
                repeat(STEPS) {
                    runBlocking { state.scrollBy(WHEEL_PIXELS) }
                    renderFrame()
                }
            }
        report("$what wheeled by $WHEEL_PIXELS px x$STEPS (net)", wheeled - still, SCROLL_BUDGET_MS, BUDGET_MB)
        assertTrue(state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 0, "$what did not scroll")
        val paged =
            costOf {
                repeat(STEPS) { step ->
                    runBlocking { state.scrollToItem((step * rows / STEPS).coerceAtMost(rows - 1)) }
                    renderFrame()
                }
            }
        report("$what scrolled through x$STEPS (net)", paged - still, SCROLL_BUDGET_MS, BUDGET_MB)
        measure("$what jumped top and bottom x$STEPS", JUMPS_BUDGET_MS, BUDGET_MB) {
            repeat(STEPS) { step ->
                runBlocking { state.scrollToItem(if (step % 2 == 0) 0 else rows - 1) }
                renderFrame()
            }
        }
    }

    @Test
    fun `hundreds of delegations show as virtualized cards, hold nothing once finished, and scroll fast`() =
        runComposeUiTest(testTimeout = 10.minutes) {
            val team = commandTeam("Crew", store, *MEMBERS.toTypedArray())
            val conversation = teamConversation(team)
            repeat(RUNS - 1) { run ->
                sendPrompt(runOf(run))
                awaitRuns(conversation, run + 1, RUN_BUDGET_MS)
            }
            measure("Run of ${ROUNDS * MEMBERS.size} delegations over ${DELEGATIONS - ROUNDS * MEMBERS.size}", RUN_BUDGET_MS, BUDGET_MB) {
                sendPrompt(runOf(RUNS - 1))
                awaitRuns(conversation, RUNS, RUN_BUDGET_MS)
                renderFrame()
            }
            val delegations = conversation.delegations()
            assertEquals(DELEGATIONS, delegations.size)
            assertTrue(delegations.all { it.status == DelegationStatus.Done }, "${delegations.map { it.status }.distinct()}")
            // Nothing is followed or kept for a finished task that nobody looks at.
            awaitCondition(AGENT_TIMEOUT) { conversation.members.transcripts.isEmpty() }
            val composed = onAllNodes(hasTestTagPrefix(CARDS), useUnmergedTree = true).fetchSemanticsNodes().size
            assertTrue(composed in 1..MAX_COMPOSED_CARDS, "$composed cards composed: the list must be virtualized")
            scrolls("Conversation of $DELEGATIONS delegations", conversation.listState, conversation.items.size)
            // A finished card reads the work of its member when it is expanded, and only then.
            runBlocking { conversation.listState.scrollToItem(conversation.items.size - 1) }
            val last = delegations.last()
            measure("Finished card expanded among $DELEGATIONS", EXPAND_BUDGET_MS, BUDGET_MB) {
                awaitTag(AgentTags.agentDelegationToggle(last.id)).performClick()
                awaitTag(AgentTags.agentDelegationWork(last.id), timeout = EXPAND_BUDGET_MS)
                renderFrame()
            }
            assertEquals(setOf(last.conversation), conversation.members.transcripts.keys.toSet())
            awaitTag(AgentTags.agentDelegationToggle(last.id)).performClick()
            awaitCondition(AGENT_TIMEOUT) { conversation.members.transcripts.isEmpty() }
        }

    @Test
    fun `a task of thousands of rows of work shows in its card and in the tab of its member, virtualized`() =
        runComposeUiTest(testTimeout = 10.minutes) {
            val team = commandTeam("Crew", store, "Backend")
            val conversation = teamConversation(team)
            // The agent processes start outside the measure.
            sendPrompt("delegate Backend: echo ready")
            awaitRuns(conversation, 1)
            measure("Task of $WORK_ROWS rows of work run and followed", WORK_BUDGET_MS, BUDGET_MB) {
                sendPrompt("delegate Backend: steps $WORK_STEPS")
                awaitRuns(conversation, 2, WORK_BUDGET_MS)
                renderFrame()
            }
            val task = conversation.delegations().last()
            assertEquals(DelegationStatus.Done, task.status)
            awaitCondition(AGENT_TIMEOUT) { conversation.members.transcripts.isEmpty() }
            // The answer of the manager, which quotes the report, pushed the card out of sight.
            runBlocking { conversation.listState.scrollToItem(conversation.items.indexOf(task)) }
            measure("Card of $WORK_ROWS rows of work expanded", EXPAND_BUDGET_MS, BUDGET_MB) {
                awaitTag(AgentTags.agentDelegationToggle(task.id)).performClick()
                awaitTag(AgentTags.agentDelegationWork(task.id), timeout = EXPAND_BUDGET_MS)
                awaitCondition(EXPAND_BUDGET_MS) { conversation.workOf(task).size > WORK_ROWS }
                renderFrame()
            }
            val inCard = onAllNodes(hasTestTagPrefix(WORK_ROW), useUnmergedTree = true).fetchSemanticsNodes().size
            assertTrue(inCard in 1..MAX_COMPOSED_WORK_ROWS, "$inCard rows composed in the card: it must be virtualized")
            scrolls("Card of $WORK_ROWS rows", conversation.members.scrollOf(task.id), WORK_ROWS)
            val member = team.members.last()
            val memberKey = conversation.memberKey(member)
            measure("Tab of a member of $WORK_ROWS rows opened", TAB_BUDGET_MS, BUDGET_MB) {
                awaitTag(AgentTags.agentMemberTab(member.id)).performClick()
                awaitTag(AgentTags.AGENT_MEMBER_TRANSCRIPT, timeout = TAB_BUDGET_MS)
                renderFrame()
            }
            val inTab = onAllNodes(hasTestTagPrefix(WORK_ROW), useUnmergedTree = true).fetchSemanticsNodes().size
            assertTrue(inTab in 1..MAX_COMPOSED_TAB_ROWS, "$inTab rows composed in the tab: it must be virtualized")
            val transcript = checkNotNull(conversation.members.transcripts[memberKey])
            scrolls("Tab of a member of $WORK_ROWS rows", transcript.listState, WORK_ROWS)
        }
}
