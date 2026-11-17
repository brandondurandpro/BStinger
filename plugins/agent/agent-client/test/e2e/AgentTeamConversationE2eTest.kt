package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.model.Team
import org.example.kotlineditor.model.TeamText
import org.example.kotlineditor.state.AgentOutcome
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.outcome
import org.example.kotlineditor.state.toPreferences
import org.example.kotlineditor.testkit.acp.FakeManager
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.CONTENT_WIDTH
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * A conversation that sends through a team: the user writes to the manager, the tasks it delegates show as cards with
 * the work of their member, the members ask their permissions in the conversation and have a tab each, Stop stops
 * everyone, and the plumbing between the manager and the harness (the `team` block, the preamble, the reports relayed)
 * never shows, live or in a conversation reopened from the history.
 */
private const val WIDE_WINDOW = 2000

@OptIn(ExperimentalTestApi::class)
class AgentTeamConversationE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    // The fake agent as the agent of the settings, with [more] preferences (the teams).
    private fun preferences(vararg more: Pair<String, String>) =
        InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + more)

    private fun crew(vararg members: String, separate: Boolean = false): Team =
        commandTeam("Crew", store, *members, separate = separate)

    // Launches the app with [team], starts a conversation and chooses the team in it.
    private fun ComposeUiTest.teamConversation(team: Team): Pair<E2eApp, Conversation> {
        val app = launchWithProject(project, preferences(globalTeams(team)))
        val conversation = startConversation(app)
        pickTeam(team.id)
        return app to conversation
    }

    private fun ComposeUiTest.isGone(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty()

    private fun memberId(name: String) = "team-Crew-$name"

    private fun lastAnswer(conversation: Conversation): String =
        conversation.itemsOf<AgentItem.AgentText>().lastOrNull()?.text.orEmpty()

    @Test
    fun `a run of two members shows the prompt as typed, a card per task and the final answer, without the team block`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend", "Tester"))
            val prompt = "delegate Backend: echo built | Tester: echo checked"
            sendPrompt(prompt)
            awaitFinalAnswer(conversation)
            assertEquals(listOf(prompt), conversation.itemsOf<AgentItem.UserText>().map { it.text })
            val delegations = conversation.delegations()
            assertEquals(listOf("Backend", "Tester"), delegations.map { it.member })
            for (delegation in delegations) {
                awaitTag(AgentTags.agentDelegation(delegation.id))
                awaitDelegationStatus(delegation, "done")
            }
            val shown = shownConversation(conversation)
            assertContains(shown, "I will delegate.")
            assertContains(shown, FakeManager.FINAL_PREFIX)
            assertContains(shown, "echo built")
            assertFalse(TeamText.BLOCK_OPENING in shown, shown)
            assertFalse("\"delegate\"" in shown, shown)
            assertFalse(TeamText.REQUEST_HEADING in shown, shown)
            // Nor in what the list of conversations and its search read.
            assertTrue(conversation.items.none { it is AgentItem.AgentText && "\"delegate\"" in it.text })
            assertEquals(AgentOutcome.Seen, conversation.outcome)
        }

    @Test
    fun `a card is folded, expands on the report and the work of its member, and folds back`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend", "Tester"))
            sendPrompt("delegate Backend: echo built | Tester: echo checked")
            awaitFinalAnswer(conversation)
            val backend = conversation.delegationOf("Backend")
            val tester = conversation.delegationOf("Tester")
            assertTrue(isGone(AgentTags.agentDelegationWork(backend.id)))
            assertTrue(isGone(AgentTags.agentDelegationReport(backend.id)))
            // Nothing of a finished member is kept until its card opens.
            awaitCondition { conversation.members.transcripts.isEmpty() }
            awaitTag(AgentTags.agentDelegationToggle(backend.id)).performClick()
            awaitTag(AgentTags.agentDelegationReport(backend.id))
            assertEquals("built", textOf(AgentTags.agentDelegationReport(backend.id)))
            awaitTag(AgentTags.agentDelegationWork(backend.id), timeout = AGENT_TIMEOUT)
            awaitCondition { "built" in textOf(AgentTags.agentDelegationWork(backend.id)) }
            val work = textOf(AgentTags.agentDelegationWork(backend.id))
            // The task as the member received it, without the preamble of the harness, then its answer.
            assertContains(work, "echo built")
            assertFalse("You are Backend" in work, work)
            assertFalse("checked" in work, work)
            assertTrue(isGone(AgentTags.agentDelegationWork(tester.id)))
            assertEquals(setOf(backend.conversation), conversation.members.transcripts.keys.toSet())
            awaitTag(AgentTags.agentDelegationToggle(backend.id)).performClick()
            awaitNoTag(AgentTags.agentDelegationWork(backend.id))
            awaitCondition { conversation.members.transcripts.isEmpty() }
        }

    @Test
    fun `a running card follows the work of its member as it comes`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend"))
            sendPrompt("delegate Backend: slow")
            awaitDelegations(conversation, 1)
            val backend = conversation.delegationOf("Backend")
            awaitDelegationStatus(backend, "running…")
            awaitTag(AgentTags.agentDelegationToggle(backend.id)).performClick()
            awaitTag(AgentTags.agentDelegationWork(backend.id), timeout = AGENT_TIMEOUT)
            awaitCondition(AGENT_TIMEOUT) { "tick 2" in textOf(AgentTags.agentDelegationWork(backend.id)) }
            awaitTag(AgentTags.AGENT_STOP).performClick()
            awaitIdle(conversation)
            awaitDelegationStatus(backend, "cancelled")
        }

    @Test
    fun `two members work at the same time, each asks its permission by name, and each is answered apart`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend", "Tester"))
            val first = project.root.resolve("BACKEND.md")
            val second = project.root.resolve("TESTER.md")
            sendPrompt("delegate Backend: write $first one | Tester: write $second two")
            awaitTag(AgentTags.agentMemberRequest("Backend"), timeout = AGENT_TIMEOUT)
            awaitTag(AgentTags.agentMemberRequest("Tester"), timeout = AGENT_TIMEOUT)
            // Both wait for the user at once: neither wrote, both cards say they work.
            val backend = conversation.delegationOf("Backend")
            val tester = conversation.delegationOf("Tester")
            awaitDelegationStatus(backend, "running…")
            awaitDelegationStatus(tester, "running…")
            assertFalse(Files.exists(first) || Files.exists(second))
            assertContains(textOf(AgentTags.agentMemberRequest("Backend")), "Backend asks to")
            assertContains(textOf(AgentTags.agentMemberRequest("Tester")), "Tester asks to")
            assertEquals(AgentOutcome.Asking, conversation.outcome)
            // The conversation itself asked nothing: the requests are the members'.
            assertTrue(conversation.itemsOf<AgentItem.Permission>().isEmpty())
            answerMember("Tester", "allow")
            awaitNoTag(AgentTags.agentMemberRequest("Tester"), timeout = AGENT_TIMEOUT)
            awaitDelegationStatus(tester, "done")
            assertEquals("two", second.readText())
            assertFalse(Files.exists(first), "Backend wrote before it was allowed")
            awaitTag(AgentTags.agentMemberRequest("Backend"))
            answerMember("Backend", "allow")
            awaitFinalAnswer(conversation)
            assertEquals("one", first.readText())
            assertTrue(isGone(AgentTags.agentMemberRequest("Backend")))
            assertEquals(listOf(DelegationStatus.Done, DelegationStatus.Done), conversation.delegations().map { it.status })
        }

    @Test
    fun `a member refused by the user does not write, and the run goes on`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend"))
            val file = project.root.resolve("REFUSED.md")
            sendPrompt("delegate Backend: write $file never")
            awaitTag(AgentTags.agentMemberRequest("Backend"), timeout = AGENT_TIMEOUT)
            answerMember("Backend", "reject")
            awaitFinalAnswer(conversation)
            assertFalse(Files.exists(file))
            assertTrue(isGone(AgentTags.agentMemberRequest("Backend")))
        }

    @Test
    fun `a second round answers the reports of the first`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend", "Tester"))
            sendPrompt("delegate Backend: echo first ;; Tester: echo second")
            awaitFinalAnswer(conversation)
            val delegations = conversation.delegations()
            assertEquals(listOf("Backend", "Tester"), delegations.map { it.member })
            assertEquals(listOf("first", "second"), delegations.map { it.result })
            // The manager spoke before each round and at the end; the reports relayed to it are no message of the user.
            val answers = conversation.itemsOf<AgentItem.AgentText>().map { it.text }
            assertEquals(listOf("I will delegate.", "I will delegate."), answers.take(2))
            assertEquals(1, conversation.itemsOf<AgentItem.UserText>().size)
            val shown = shownConversation(conversation)
            assertFalse(TeamText.RELAY_OPENING in shown, shown)
        }

    @Test
    fun `Stop cancels the manager and every member, and the cards say so`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend", "Tester"))
            sendPrompt("delegate Backend: slow | Tester: slow")
            awaitDelegations(conversation, 2)
            val backend = conversation.delegationOf("Backend")
            val tester = conversation.delegationOf("Tester")
            awaitDelegationStatus(backend, "running…")
            awaitDelegationStatus(tester, "running…")
            awaitCondition(AGENT_TIMEOUT) { conversation.members.transcripts.size == 2 }
            awaitTag(AgentTags.AGENT_STOP).performClick()
            awaitIdle(conversation)
            awaitDelegationStatus(backend, "cancelled")
            awaitDelegationStatus(tester, "cancelled")
            val notices = conversation.itemsOf<AgentItem.Notice>().map { it.text }
            assertEquals(listOf("Stopped."), notices)
            assertFalse(conversation.itemsOf<AgentItem.AgentText>().any { FakeManager.FINAL_PREFIX in it.text })
            // Nothing of the members stays followed once they stopped.
            awaitCondition { conversation.members.transcripts.isEmpty() }
            assertEquals("idle", textOf(AgentTags.agentMemberTabStatus(memberId("Backend"))))
        }

    @Test
    fun `a member that fails fails its card only, the other succeeds and the manager answers`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend", "Tester", separate = true))
            sendPrompt("delegate Backend: crash | Tester: echo fine")
            awaitFinalAnswer(conversation)
            val backend = conversation.delegationOf("Backend")
            val tester = conversation.delegationOf("Tester")
            awaitDelegationStatus(backend, "failed")
            awaitDelegationStatus(tester, "done")
            assertEquals("fine", tester.result)
            val final = lastAnswer(conversation)
            assertContains(final, "## Backend (failed)")
            assertContains(final, "## Tester (done)")
            assertEquals("failed", textOf(AgentTags.agentMemberTabStatus(memberId("Backend"))))
            assertEquals("idle", textOf(AgentTags.agentMemberTabStatus(memberId("Tester"))))
            // The reason shows in the card, without reading the transcript of the member.
            awaitTag(AgentTags.agentDelegationToggle(backend.id)).performClick()
            awaitTag(AgentTags.agentDelegationReport(backend.id))
            assertTrue(textOf(AgentTags.agentDelegationReport(backend.id)).isNotBlank())
        }

    @Test
    fun `a malformed block is told to the manager, which recovers, and none of it shows`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend"))
            sendPrompt("delegate-malformed")
            awaitFinalAnswer(conversation)
            assertContains(lastAnswer(conversation), "not valid JSON")
            assertTrue(conversation.delegations().isEmpty())
            assertEquals(listOf("delegate-malformed"), conversation.itemsOf<AgentItem.UserText>().map { it.text })
            val shown = shownConversation(conversation)
            assertFalse("oops" in shown, shown)
            assertFalse(TeamText.REFUSAL_OPENING in shown, shown)
        }

    @Test
    fun `an unknown member is told to the manager, which recovers`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend"))
            sendPrompt("delegate Nobody: echo hi")
            awaitFinalAnswer(conversation)
            assertContains(lastAnswer(conversation), "There is no member named \"Nobody\"")
            assertTrue(conversation.delegations().isEmpty())
            assertEquals(1, conversation.itemsOf<AgentItem.UserText>().size)
        }

    @Test
    fun `a prompt sent during a run shows at once and runs after it`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend"))
            val file = project.root.resolve("HELD.md")
            sendPrompt("delegate Backend: write $file held")
            awaitTag(AgentTags.agentMemberRequest("Backend"), timeout = AGENT_TIMEOUT)
            sendPrompt("echo afterwards")
            awaitShown(conversation, "echo afterwards")
            assertFalse(conversation.itemsOf<AgentItem.AgentText>().any { "afterwards" in it.text })
            answerMember("Backend", "allow")
            awaitAnswer(conversation, "afterwards")
            awaitIdle(conversation)
            val texts = conversation.itemsOf<AgentItem.AgentText>().map { it.text }
            val final = texts.indexOfFirst { FakeManager.FINAL_PREFIX in it }
            val later = texts.indexOfFirst { it.startsWith("afterwards") }
            assertTrue(final in 0 until later, "The second prompt ran before the end of the run: $texts")
            assertEquals(2, conversation.itemsOf<AgentItem.UserText>().size)
            assertEquals(1, conversation.delegations().size)
        }

    @Test
    fun `the transcript of a member is centred in the window like the conversation, no wider than its text column`() =
        // A wide window: the text column is narrower than the pane, which is what shows where the text is.
        runDesktopComposeUiTest(width = WIDE_WINDOW, height = 900) {
            val (_, conversation) = teamConversation(crew("Backend"))
            val file = project.root.resolve("CENTRE.md")
            sendPrompt("delegate Backend: write $file centre")
            awaitTag(AgentTags.agentMemberRequest("Backend"), timeout = AGENT_TIMEOUT)
            val pane = boundsOf(AgentTags.agentView(conversation.id))
            awaitTag(AgentTags.agentMemberTab(memberId("Backend"))).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_TRANSCRIPT, timeout = AGENT_TIMEOUT)
            val text = boundsOf(AgentTags.AGENT_MEMBER_TRANSCRIPT)
            val widest = with(density) { CONTENT_WIDTH.toPx() }
            assertTrue(pane.width > widest + 100, "the pane (${pane.width}px) is not wider than the text column (${widest}px)")
            assertTrue(text.width <= widest + 1, "the transcript is ${text.width}px wide, more than the ${widest}px of a conversation")
            val left = text.left - pane.left
            val right = pane.right - text.right
            assertTrue(kotlin.math.abs(left - right) <= 2f, "the transcript is not centred: $left px at its left, $right px at its right")
            answerPermission("allow")
            awaitCondition(AGENT_TIMEOUT) { textOf(AgentTags.AGENT_MEMBER_STATUS) == "idle" }
        }

    @Test
    fun `each member has a tab with its status, and the tab shows its transcript and takes its answer`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend", "Tester"))
            val backendTab = AgentTags.agentMemberTab(memberId("Backend"))
            val backendStatus = AgentTags.agentMemberTabStatus(memberId("Backend"))
            val testerStatus = AgentTags.agentMemberTabStatus(memberId("Tester"))
            awaitTag(backendTab)
            assertEquals("idle", textOf(backendStatus))
            assertEquals("idle", textOf(testerStatus))
            val file = project.root.resolve("TAB.md")
            sendPrompt("delegate Backend: write $file tab")
            awaitTag(AgentTags.agentMemberRequest("Backend"), timeout = AGENT_TIMEOUT)
            awaitCondition { textOf(backendStatus) == "working" }
            assertEquals("idle", textOf(testerStatus))
            awaitTag(backendTab).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_VIEW)
            assertEquals("working", textOf(AgentTags.AGENT_MEMBER_STATUS))
            awaitTag(AgentTags.AGENT_MEMBER_TRANSCRIPT, timeout = AGENT_TIMEOUT)
            assertContains(textOf(AgentTags.AGENT_MEMBER_TRANSCRIPT), "write $file tab")
            // The conversation is not shown behind the tab: the request is answered in the member's own view.
            assertTrue(isGone(AgentTags.agentMemberRequest("Backend")))
            answerPermission("allow")
            awaitCondition(AGENT_TIMEOUT) { textOf(AgentTags.AGENT_MEMBER_STATUS) == "idle" }
            assertEquals("tab", file.readText())
            awaitCondition { "Wrote" in textOf(AgentTags.AGENT_MEMBER_TRANSCRIPT) }
            // A member without a task says so.
            awaitTag(AgentTags.agentMemberTab(memberId("Tester"))).performClick()
            awaitTag(AgentTags.AGENT_MEMBER_EMPTY, timeout = AGENT_TIMEOUT)
            awaitTag(AgentTags.agentDetailChat(conversation.id)).performClick()
            awaitNoTag(AgentTags.AGENT_MEMBER_VIEW)
            awaitFinalAnswer(conversation)
            awaitCondition { conversation.members.transcripts.isEmpty() }
        }

    @Test
    fun `a conversation whose team was deleted sends nothing and says why`() =
        runComposeUiTest {
            val team = crew("Backend")
            val (_, conversation) = teamConversation(team)
            sendPrompt("delegate Backend: echo built")
            awaitFinalAnswer(conversation)
            openTeams()
            awaitTag(AgentTags.agentTeamDelete(team.id)).performClick()
            awaitTag(AgentTags.agentTeamDelete(team.id)).performClick()
            awaitNoTag(AgentTags.agentTeam(team.id))
            awaitTag(AgentTags.AGENT_TEAMS_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_TEAMS_DIALOG)
            awaitTag(AgentTags.AGENT_TEAM_MISSING)
            val before = conversation.items.toList()
            typePrompt("echo to nobody")
            awaitTag(AgentTags.AGENT_SEND).assertIsNotEnabled()
            pressInPrompt(Key.Enter)
            waitForIdle()
            // Nothing was sent, to the team or to another agent, and what was typed is kept.
            assertEquals("echo to nobody", conversation.draft)
            assertEquals(before, conversation.items.toList())
            assertFalse(conversation.busy)
        }

    @Test
    fun `the mode of permissions chosen in the conversation reaches the members`() =
        runComposeUiTest {
            val (_, conversation) = teamConversation(crew("Backend"))
            awaitPermissions("Manual")
            choosePermissions("accept-edits")
            awaitPermissions("Accept edits")
            val file = project.root.resolve("ACCEPTED.md")
            sendPrompt("delegate Backend: write $file accepted")
            awaitFinalAnswer(conversation)
            assertEquals("accepted", file.readText())
            // The member wrote without asking.
            assertEquals(DelegationStatus.Done, conversation.delegationOf("Backend").status)
            assertTrue(isGone(AgentTags.agentMemberRequest("Backend")))
        }

    @Test
    fun `a conversation reopened after a restart reads as typed, without the preamble or the prompts of the harness`() {
        val team = crew("Backend")
        var saved: Map<String, String> = emptyMap()
        var session = ""
        runComposeUiTest(testTimeout = 3.minutes) {
            val (app, conversation) = teamConversation(team)
            sendPrompt("delegate Backend: echo built")
            awaitFinalAnswer(conversation)
            sendPrompt("echo again")
            awaitAnswer(conversation, "again")
            awaitIdle(conversation)
            val key = PreferenceKeys.AGENT_CONVERSATION_TEAMS_PREFIX + app.project.root.path
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.toPreferences()[key].orEmpty().isNotBlank() }
            // The session the member worked in is learned when its transcript is read, which a task that ended between
            // two reads of the conversation did not cause: the card is opened, as a user does, to read it.
            val delegation = checkNotNull(conversation.items.filterIsInstance<AgentItem.Delegation>().firstOrNull())
            if (app.project.agent.memberSessions.isEmpty()) awaitTag(AgentTags.agentDelegationToggle(delegation.id)).performClick()
            awaitCondition(AGENT_TIMEOUT) { app.project.agent.memberSessions.isNotEmpty() }
            saved = app.project.agent.toPreferences()
            session = checkNotNull(conversation.session) { "No session" }
        }
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launchWithProject(project, InMemoryPreferenceStore(preferences(globalTeams(team)).load() + saved))
            val conversation = awaitHistory(app, session)
            awaitCondition(AGENT_TIMEOUT) { conversation.teamId == team.id }
            awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
            awaitAnswer(conversation, "again")
            awaitIdle(conversation)
            assertEquals(team.manager.agent, app.project.agent.agentOf(conversation))
            val typed = conversation.itemsOf<AgentItem.UserText>().map { it.text }
            assertEquals(listOf("delegate Backend: echo built", "echo again"), typed)
            val shown = shownConversation(conversation)
            assertContains(shown, FakeManager.FINAL_PREFIX)
            assertFalse("You are Lead" in shown, shown)
            assertFalse(TeamText.RELAY_OPENING in typed.joinToString("\n"), shown)
            assertFalse(TeamText.BLOCK_OPENING in shown, shown)
            assertFalse("\"delegate\"" in shown, shown)
            // The team still answers: the next prompt is a run of it.
            sendPrompt("delegate Backend: echo more")
            awaitCondition(AGENT_TIMEOUT) { conversation.delegations().any { it.status == DelegationStatus.Done } }
            awaitIdle(conversation)
            assertEquals("more", conversation.delegationOf("Backend").result)
            // The session the member worked in is part of this conversation, not another one of the history.
            val listed = app.project.agent.conversations.map { it.title }
            assertEquals(listOf("delegate Backend: echo built"), listed)
        }
    }
}
