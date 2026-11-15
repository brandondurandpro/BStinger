package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.state.AgentState
import org.example.kotlineditor.state.AgentTeam
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.OrchestrationMode
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.TeamMember
import org.example.kotlineditor.state.decodeTeams
import org.example.kotlineditor.state.encodeTeams
import org.example.kotlineditor.state.toPreferences
import org.example.kotlineditor.state.withSavedModes
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun relayTeam(mode: OrchestrationMode = OrchestrationMode.Relay) =
    AgentTeam(
        "Crew",
        TeamMember("Boss"),
        listOf(TeamMember("Dev", "Smart", "high"), TeamMember("Tester")),
        mode,
    )

/**
 * The IDE's relay: the manager of a team in relay mode gets the tool `delegate` of the IDE's server, which runs each
 * member in a session of its own; the strip shows the member, whose transcript the popup follows.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRelayE2eTest {
    private val project = SampleProject.standard().apply { commitAll() }
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun preferences() = agentPreferences("--store", "$store")

    // Makes the team [team], chooses it for a new conversation and spends its briefing on a greeting.
    private fun ComposeUiTest.startTeam(team: AgentTeam): Triple<E2eApp, AgentState, Conversation> {
        val app = launchWithProject(project, preferences())
        val conversation = startConversation(app)
        val agent = app.project.agent
        agent.saveTeam(null, team)
        awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
        awaitTag(AgentTags.agentTeam(team.name)).performClick()
        sendPrompt("hello")
        awaitShown(conversation, "You said")
        awaitIdle(conversation)
        return Triple(app, agent, conversation)
    }

    private fun ComposeUiTest.openChip(name: String): String {
        awaitTag(AgentTags.agentStripEntry(name), timeout = AGENT_TIMEOUT).performClick()
        awaitTag(AgentTags.AGENT_STRIP_DETAIL)
        return textOf(AgentTags.AGENT_STRIP_DETAIL)
    }

    private fun ComposeUiTest.closeDetail() {
        awaitTag(AgentTags.AGENT_STRIP_DETAIL_CLOSE).performClick()
        awaitNoTag(AgentTags.AGENT_STRIP_DETAIL)
    }

    @Test
    fun `IDE relay is chosen in the team window and saved, an agent team stays an agent team`() =
        runComposeUiTest {
            val prefs = preferences()
            val app = launchWithProject(project, prefs)
            startConversation(app)
            val agent = app.project.agent
            awaitTag(AgentTags.AGENT_TEAMS, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Relayed")
            awaitTag(AgentTags.AGENT_TEAM_MODE_RELAY).performClick()
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.any { it.name == "Relayed" } }
            assertEquals(OrchestrationMode.Relay, agent.teams.first { it.name == "Relayed" }.mode)
            assertEquals(OrchestrationMode.Agent, agent.teams.first { it.name == "Default team" }.mode)
            // What the app saves of it: read back, the team is a relay team again.
            val saved = agent.toPreferences()
            assertEquals("Relayed\tRelay", saved[PreferenceKeys.AGENT_TEAM_MODES])
            val reloaded = withSavedModes(decodeTeams(saved[PreferenceKeys.AGENT_TEAMS]), saved[PreferenceKeys.AGENT_TEAM_MODES])
            assertEquals(OrchestrationMode.Relay, reloaded.first { it.name == "Relayed" }.mode)
            // Edited back to the agent's mode.
            awaitTag(AgentTags.agentTeamEdit("Relayed")).performClick()
            awaitTag(AgentTags.AGENT_TEAM_MODE_AGENT).performClick()
            awaitTag(AgentTags.AGENT_TEAM_SAVE).performClick()
            awaitCondition { agent.teams.first { it.name == "Relayed" }.mode == OrchestrationMode.Agent }
        }

    @Test
    fun `preferences saved before the modes existed load every team as an agent team`() =
        runComposeUiTest {
            val old = AgentTeam("Old", TeamMember("Boss"), listOf(TeamMember("Dev")))
            val prefs = preferences()
            prefs.save(prefs.load() + (PreferenceKeys.AGENT_TEAMS to encodeTeams(listOf(old))))
            assertFalse(PreferenceKeys.AGENT_TEAM_MODES in prefs.load())
            val app = launchWithProject(project, prefs)
            startConversation(app)
            val agent = app.project.agent
            assertEquals(listOf("Old"), agent.teams.map { it.name })
            assertEquals(OrchestrationMode.Agent, agent.teams.single().mode)
        }

    @Test
    fun `the mode of a conversation follows its team, changes before the first prompt and is fixed after`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            val conversation = startConversation(app)
            val agent = app.project.agent
            agent.saveTeam(null, relayTeam(OrchestrationMode.Agent))
            awaitTag(AgentTags.AGENT_TEAM, timeout = AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentTeam("Crew")).performClick()
            assertEquals("Agent ▾", textOf(AgentTags.AGENT_TEAM_MODE))
            awaitTag(AgentTags.AGENT_TEAM_MODE).performClick()
            awaitTag(AgentTags.AGENT_TEAM_MODE_RELAY).performClick()
            awaitCondition { textOf(AgentTags.AGENT_TEAM_MODE) == "IDE relay ▾" }
            awaitTag(AgentTags.AGENT_STRIP_RELAY)
            // The team itself is not changed by the choice of a conversation.
            assertEquals(OrchestrationMode.Agent, agent.teams.first { it.name == "Crew" }.mode)
            sendPrompt("hello")
            awaitShown(conversation, "You said")
            awaitIdle(conversation)
            awaitTag(AgentTags.AGENT_TEAM_MODE).performClick()
            awaitNoTag(AgentTags.AGENT_TEAM_MODE_AGENT)
            assertEquals("IDE relay ▾", textOf(AgentTags.AGENT_TEAM_MODE))
        }

    @Test
    fun `a relay manager is started with the delegate server and without the launchers of its agent`() =
        runComposeUiTest {
            val (_, _, conversation) = startTeam(relayTeam())
            sendPrompt("setup")
            awaitShown(conversation, "kotlin-editor-team")
            val shown = shownConversation(conversation)
            assertTrue("\"type\":\"http\"" in shown && "Bearer" in shown, shown)
            assertTrue("disallowedTools" in shown && "Task" in shown && "Agent" in shown, shown)
            awaitIdle(conversation)
        }

    @Test
    fun `an agent team is started without the delegate server`() =
        runComposeUiTest {
            val (_, _, conversation) = startTeam(relayTeam(OrchestrationMode.Agent))
            sendPrompt("setup")
            awaitShown(conversation, "mcpServers")
            val shown = shownConversation(conversation)
            assertFalse("kotlin-editor-team" in shown || "disallowedTools" in shown, shown)
            awaitIdle(conversation)
        }

    @Test
    fun `a delegation shows its member in the strip, whose popup follows its transcript and gives its report`() =
        runComposeUiTest {
            val (_, agent, conversation) = startTeam(relayTeam())
            sendPrompt("delegate-via-mcp Dev echo hi")
            val live = openChip("Dev")
            awaitCondition(AGENT_TIMEOUT) { "hi" in textOf(AgentTags.AGENT_STRIP_DETAIL).substringAfter("Report") }
            val shown = textOf(AgentTags.AGENT_STRIP_DETAIL)
            assertTrue("echo hi" in shown, "the member's transcript shows its task: $shown")
            assertTrue("Report" in shown, shown + live)
            closeDetail()
            awaitIdle(conversation)
            awaitTag(AgentTags.AGENT_STRIP_RELAY)
            // The manager's popup does not list the delegate call; the child is no conversation of the list.
            val manager = openChip("Boss")
            assertTrue("No command yet." in manager, manager)
            closeDetail()
            assertEquals(1, agent.conversations.size)
            assertTrue(agent.conversations.none { it.key.contains("~d") })
            assertTrue(agent.delegations.isEmpty(), "the follower stops with the dialog: ${agent.delegations.keys}")
        }

    @Test
    fun `the member runs with the model and the effort of the team`() =
        runComposeUiTest {
            val (_, _, conversation) = startTeam(relayTeam())
            sendPrompt("delegate-via-mcp Dev effort")
            val call = awaitDelegateCall(conversation)
            awaitCondition(AGENT_TIMEOUT) { call().status == AgentToolStatus.Completed }
            assertTrue("Effort: high." in call().output, call().output)
            awaitIdle(conversation)
        }

    @Test
    fun `the member runs on the model of the team`() =
        runComposeUiTest {
            val (_, _, conversation) = startTeam(relayTeam())
            sendPrompt("delegate-via-mcp Dev model")
            val call = awaitDelegateCall(conversation)
            awaitCondition(AGENT_TIMEOUT) { call().status == AgentToolStatus.Completed }
            assertTrue("Model: fake-smart." in call().output, call().output)
            awaitIdle(conversation)
        }

    private fun ComposeUiTest.awaitDelegateCall(conversation: Conversation): () -> AgentItem.ToolCall {
        val find = { conversation.itemsOf<AgentItem.ToolCall>().firstOrNull { it.title.startsWith("delegate") } }
        awaitCondition(AGENT_TIMEOUT) { find() != null }
        return { checkNotNull(find()) }
    }

    @Test
    fun `a delegation the manager gives up on stops its member, whose popup says why`() =
        runComposeUiTest {
            val (_, _, conversation) = startTeam(relayTeam())
            // The manager gives up its call after 3 s, as Claude does after its tool timeout; the member would tick for
            // much longer than the test waits.
            sendPrompt("delegate-via-mcp-timeout 3000 Dev slow")
            openChip("Dev")
            val notice = "The manager stopped waiting for this delegation"
            awaitCondition(AGENT_TIMEOUT) { notice in textOf(AgentTags.AGENT_STRIP_DETAIL) }
            closeDetail()
            awaitShown(conversation, "The delegation timed out.")
            awaitIdle(conversation)
            val call = awaitDelegateCall(conversation)
            assertEquals(AgentToolStatus.Failed, call().status)
            // The member stopped: the next delegation does not wait behind it.
            sendPrompt("delegate-via-mcp Tester echo next")
            awaitShown(conversation, "The member answered.")
            awaitIdle(conversation)
        }

    @Test
    fun `an unknown member gives the manager an error result and nothing else happens`() =
        runComposeUiTest {
            val (_, _, conversation) = startTeam(relayTeam())
            sendPrompt("delegate-via-mcp Ghost do it")
            val call = awaitDelegateCall(conversation)
            awaitCondition(AGENT_TIMEOUT) { call().status == AgentToolStatus.Failed }
            assertTrue("There is no member named \"Ghost\"" in call().output, call().output)
            awaitIdle(conversation)
            assertTrue(conversation.itemsOf<AgentItem.Notice>().isEmpty(), "${conversation.items}")
            sendPrompt("echo still fine")
            awaitShown(conversation, "still fine")
            awaitIdle(conversation)
        }

    @Test
    fun `a permission of the member is answered in its popup, and its file is written once and alone`() =
        runComposeUiTest {
            val before = DiskSnapshot.take(project)
            val (_, _, conversation) = startTeam(relayTeam())
            val file = project.root.resolve("relay-out.txt")
            sendPrompt("delegate-via-mcp Dev write $file made-by-dev")
            openChip("Dev")
            answerPermission("allow")
            awaitIdle(conversation)
            awaitCondition(AGENT_TIMEOUT) { Files.exists(file) }
            assertEquals("made-by-dev", Files.readString(file))
            closeDetail()
            before.assertUnchanged(project, except = setOf("relay-out.txt"))
            assertFalse(Files.exists(project.root.resolve(".claude")), "the relay writes no settings in the project")
        }

    @Test
    fun `the session of a member is not listed with the conversations, even after the server restarts`() =
        runComposeUiTest {
            val (app, agent, conversation) = startTeam(relayTeam())
            sendPrompt("delegate-via-mcp Dev echo hi")
            awaitShown(conversation, "The member answered.")
            awaitIdle(conversation)
            val manager = checkNotNull(conversation.session)
            awaitCondition(AGENT_TIMEOUT) { storedSessions().size == 2 }
            val member = storedSessions().firstOrNull { it != manager }
            app.process.restart()
            // A session made meanwhile: once it is listed, the history was listed again, by the restarted server.
            val other = pastSession(store, app.project.root.path, listOf("echo other" to "other "))
            awaitTag(AgentTags.MODE_IDE).performClick()
            awaitHistory(app, other)
            val listed = agent.conversations.mapNotNull { it.session }
            assertFalse(member in listed, "the member's session $member is listed: $listed")
            assertTrue(agent.conversations.none { it.key.contains("~d") })
        }

    // The ids of the sessions the agent keeps on its disk.
    private fun storedSessions(): List<String> =
        Files.list(store).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }.toList()
        }

    @Test
    fun `deleting the conversation stops following its members`() =
        runComposeUiTest {
            val (_, agent, conversation) = startTeam(relayTeam())
            sendPrompt("delegate-via-mcp Dev echo hi")
            openChip("Dev")
            awaitIdle(conversation)
            assertTrue(agent.delegations.isNotEmpty())
            agent.delete(conversation)
            awaitCondition { agent.delegations.isEmpty() }
        }
}
