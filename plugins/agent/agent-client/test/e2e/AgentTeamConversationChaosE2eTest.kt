package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.testkit.acp.FakeManager
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val RESTARTED = "The server restarted: the team stopped, the tasks of its members are cancelled."

/**
 * A team conversation when the network or the server fails: the run goes on on the server and the IDE catches up, a
 * prompt and an answer to a member are applied once, a restarted server is explained, and no card stays running or
 * loading.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamConversationChaosE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")
    private val team = commandTeam("Crew", store, "Backend", "Tester")

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

    // The numbers of the ticks of the slow work of the member of [delegation], in the order its transcript has them.
    private fun ticks(conversation: Conversation, delegation: AgentItem.Delegation): List<Int> {
        val work = conversation.members.transcripts[delegation.conversation]?.items.orEmpty()
        val said = work.filterIsInstance<AgentItem.AgentText>().joinToString("") { it.text }
        return Regex("tick (\\d+)").findAll(said).map { it.groupValues[1].toInt() }.toList()
    }

    // A member at slow work, its card running: the run to disturb.
    private fun ComposeUiTest.slowTask(conversation: Conversation): AgentItem.Delegation {
        sendPrompt("delegate Backend: slow")
        awaitDelegations(conversation, 1)
        val task = conversation.delegationOf("Backend")
        awaitDelegationStatus(task, "running…")
        awaitCondition(AGENT_TIMEOUT) { ticks(conversation, task).size > 1 }
        return task
    }

    // The work went on while the IDE could not see it, and all of it shows, once, in order; then the run is stopped.
    private fun ComposeUiTest.assertCaughtUp(conversation: Conversation, task: AgentItem.Delegation, seen: Int) {
        awaitCondition(AGENT_TIMEOUT) { ticks(conversation, task).size > seen + 10 }
        val shown = ticks(conversation, task)
        assertEquals((0 until shown.size).toList(), shown, "every tick once, in order")
        awaitTag(AgentTags.AGENT_STOP).performClick()
        awaitIdle(conversation)
        awaitDelegationStatus(task, "cancelled")
        assertEquals(listOf("Stopped."), conversation.itemsOf<AgentItem.Notice>().map { it.text })
    }

    @Test
    fun `a run goes on while the network is down, and the conversation catches up when it is back`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            val task = slowTask(conversation)
            app.network.goDown()
            awaitReconnecting()
            val seen = ticks(conversation, task).size
            app.network.comeBack()
            awaitReconnected()
            assertCaughtUp(conversation, task, seen)
        }

    @Test
    fun `a link that goes silent during a run is noticed, and the conversation catches up after it`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            val task = slowTask(conversation)
            app.network.blackHole = true
            awaitReconnecting()
            val seen = ticks(conversation, task).size
            app.network.blackHole = false
            app.network.cutAll()
            awaitReconnected(AGENT_TIMEOUT)
            assertCaughtUp(conversation, task, seen)
        }

    @Test
    fun `a prompt whose answer was lost runs its team once`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            // The agents start, and each has a session, before the link fails.
            sendPrompt("delegate Backend: count")
            awaitRuns(conversation, 1)
            app.network.dropFromServer = true
            sendPrompt("delegate Backend: count")
            // The server ran the request: its manager was prompted (the fake agent keeps its sessions in the store).
            awaitCondition(AGENT_TIMEOUT) { promptsKept() >= 2 }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected(AGENT_TIMEOUT)
            awaitRuns(conversation, 2)
            assertEquals(2, conversation.itemsOf<AgentItem.UserText>().size)
            assertEquals(2, conversation.delegations().size)
            // The member was prompted twice in all: once per run.
            assertContains(conversation.delegationOf("Backend").result, "Prompt 2 of")
            val finals = conversation.itemsOf<AgentItem.AgentText>().count { it.text.startsWith(FakeManager.FINAL_PREFIX) }
            assertEquals(2, finals)
        }

    // How many times the sessions of the store were given the prompt of the user of this test.
    private fun promptsKept(): Int {
        val files = store.toFile().listFiles { file -> file.extension == "json" }.orEmpty()
        return files.sumOf { file -> Regex("delegate Backend: count").findAll(file.readText()).count() }
    }

    @Test
    fun `on a slow link the cards end in order, nothing goes back, and a prompt typed meanwhile runs after`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            app.network.latencyMs = 300
            sendPrompt("delegate Backend: echo one | Tester: echo two ;; Backend: echo three")
            // Typing while the team works is not blocked by the link: it waits, and runs once the run is over.
            sendPrompt("echo afterwards")
            awaitRuns(conversation, 1)
            awaitAnswer(conversation, "afterwards")
            awaitIdle(conversation)
            app.network.latencyMs = 0
            val tasks = conversation.delegations()
            assertEquals(listOf("Backend", "Tester", "Backend"), tasks.map { it.member })
            assertEquals(listOf("one", "two", "three"), tasks.map { it.result.trim() })
            assertTrue(tasks.all { it.status == DelegationStatus.Done }, "${tasks.map { it.status }}")
            for (task in tasks) awaitDelegationStatus(task, "done")
            val typed = conversation.itemsOf<AgentItem.UserText>().map { it.text }
            assertEquals(listOf("delegate Backend: echo one | Tester: echo two ;; Backend: echo three", "echo afterwards"), typed)
            assertEquals("", conversation.draft)
        }

    @Test
    fun `a server that restarts during a run cancels its cards, says so, and the next prompt runs`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            val task = slowTask(conversation)
            // The card is open on the work of its member when the server goes.
            awaitTag(AgentTags.agentDelegationToggle(task.id)).performClick()
            awaitTag(AgentTags.agentDelegationWork(task.id), timeout = AGENT_TIMEOUT)
            app.process.restart()
            awaitReconnected(AGENT_TIMEOUT)
            awaitIdle(conversation)
            awaitDelegationStatus(task, "cancelled")
            assertContains(conversation.itemsOf<AgentItem.Notice>().map { it.text }, RESTARTED)
            // Nothing stays loading: the card shows what it had, or says the work is gone, and nothing is followed.
            awaitCondition(AGENT_TIMEOUT) { conversation.members.transcripts.values.all { it.loaded && it.job?.isActive != true } }
            assertFalse(conversation.delegations().any { it.status == DelegationStatus.Running })
            sendPrompt("delegate Backend: echo again")
            awaitRuns(conversation, 1)
            val next = conversation.delegationOf("Backend")
            awaitDelegationStatus(next, "done")
            assertEquals("again", next.result.trim())
            awaitDelegationStatus(task, "cancelled")
        }

    @Test
    fun `the answer to a member that was lost on the way back is applied once`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation()
            val file = project.root.resolve("NOTES.md")
            sendPrompt("delegate Backend: write $file once")
            awaitTag(AgentTags.agentMemberRequest("Backend"), timeout = AGENT_TIMEOUT)
            app.network.dropFromServer = true
            answerMember("Backend", "allow")
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected(AGENT_TIMEOUT)
            awaitRuns(conversation, 1)
            assertEquals("once", file.readText())
            val task = conversation.delegationOf("Backend")
            awaitDelegationStatus(task, "done")
            assertEquals(1, conversation.delegations().size)
            awaitNoTag(AgentTags.agentMemberRequest("Backend"))
            // The member was asked once and wrote once.
            awaitTag(AgentTags.agentDelegationToggle(task.id)).performClick()
            awaitTag(AgentTags.agentDelegationWork(task.id), timeout = AGENT_TIMEOUT)
            val work = conversation.members.transcripts[task.conversation]?.items.orEmpty()
            val asked = work.filterIsInstance<AgentItem.Permission>()
            assertEquals(listOf("allow"), asked.map { it.chosen })
            assertEquals(1, work.filterIsInstance<AgentItem.AgentText>().count { it.text.startsWith("Wrote") })
        }
}
