package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.TreeTags
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Agent mode with a real ACP agent (the fake one of testkit, in its own process) run by the server: answers stream
 * in, tool calls and permission requests show, the files it writes show in the project, Stop stops it.
 */
@OptIn(ExperimentalTestApi::class)
class AgentE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `an answer streams into the conversation, under the name of the agent`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo hello from the agent")
            awaitShown(conversation, "hello from the agent")
            awaitIdle(conversation)
            assertEquals("Fake Agent 1.0", textOf(AppTags.AGENT_NAME))
            assertEquals(1, conversation.itemsOf<AgentItem.AgentText>().size)
            assertEquals(
                listOf("echo hello from the agent"),
                conversation.itemsOf<AgentItem.UserText>().map { it.text },
            )
            assertEquals("echo hello from the agent", conversation.title)
        }

    @Test
    fun `a blank prompt sends nothing`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("   ")
            waitForIdle()
            assertTrue(conversation.items.isEmpty())
            assertFalse(conversation.busy)
        }

    @Test
    fun `a write asks first, then the file shows in the project`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val file = project.root.resolve("NOTES.md")
            sendPrompt("write $file Notes of the agent")
            awaitShown(conversation, "The agent asks to: Write")
            assertFalse(file.exists())
            answerPermission("allow")
            awaitShown(conversation, "Wrote")
            awaitIdle(conversation)
            assertEquals("Notes of the agent", file.readText())
            assertContains(shownConversation(conversation), "+ Notes of the agent")
            assertContains(shownConversation(conversation), "Answered: Allow")
            // The tree picks the new file up without waiting for the window to regain the focus.
            awaitTag(AppTags.MODE_IDE).performClick()
            awaitTag(TreeTags.treeRow("NOTES.md"), timeout = AGENT_TIMEOUT)
        }

    @Test
    fun `a rejected write leaves the disk alone`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val file = project.root.resolve("NOTES.md")
            sendPrompt("write $file never")
            answerPermission("reject")
            awaitShown(conversation, "Not written.")
            awaitIdle(conversation)
            assertFalse(file.exists())
            val call = conversation.itemsOf<AgentItem.ToolCall>().single()
            assertEquals(AgentToolStatus.Failed, call.status)
        }

    @Test
    fun `stop cancels the answer and the question the agent asked`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("slow")
            awaitShown(conversation, "tick 2")
            onNodeWithTag(AppTags.AGENT_STOP).performClick()
            awaitShown(conversation, "Stopped.")
            awaitIdle(conversation)
            val file = project.root.resolve("NOTES.md")
            sendPrompt("write $file never")
            awaitTag(AppTags.agentPermission("allow"), timeout = AGENT_TIMEOUT)
            onNodeWithTag(AppTags.AGENT_STOP).performClick()
            awaitShown(conversation, "Cancelled")
            awaitIdle(conversation)
            awaitNoTag(AppTags.agentPermission("allow"))
            assertFalse(file.exists())
        }

    @Test
    fun `the agent reads the modified files as the editor shows them`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            openFromTree("README.md")
            onNodeWithTag(EditorTags.EDITOR).performKeyInput {
                pressKey(Key.MoveEnd)
                pressKey(Key.Enter)
                pressKey(Key.Enter)
            }
            awaitCondition {
                app.project.editor.activeTab
                    ?.document
                    ?.isModified == true
            }
            val conversation = startConversation(app)
            sendPrompt("read ${project.root.resolve("README.md")}")
            awaitShown(conversation, "Read")
            awaitIdle(conversation)
            assertEquals(6, project.read("README.md").lines().size)
            assertContains(shownConversation(conversation), "Read 6 lines.")
        }

    @Test
    fun `a crash of the agent is explained, and the next prompt starts a new session`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("crash")
            awaitShown(conversation, "exit code 3")
            awaitIdle(conversation)
            assertContains(shownConversation(conversation), "fake agent crashed")
            sendPrompt("count")
            awaitShown(conversation, "Prompt 1 of")
            assertContains(shownConversation(conversation), "no longer remembers the previous messages")
        }

    @Test
    fun `an agent that cannot start is explained`() =
        runComposeUiTest {
            val preferences =
                InMemoryPreferenceStore(
                    mapOf(PreferenceKeys.AGENT_MODE to "true", PreferenceKeys.AGENT_COMMAND to "no-such-agent-42"),
                )
            val app = launchWithProject(project, preferences)
            val conversation = startConversation(app)
            sendPrompt("echo hi")
            awaitShown(conversation, "Cannot start the agent no-such-agent-42")
            awaitIdle(conversation)
        }

    @Test
    fun `an agent that needs a login says how`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--login"))
            val conversation = startConversation(app)
            sendPrompt("echo hi")
            awaitShown(conversation, "Log in to the agent first")
            awaitIdle(conversation)
            assertContains(shownConversation(conversation), "Run fake-login in a terminal")
        }

    @Test
    fun `the plan and the tool calls of the agent show as they progress`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("plan")
            awaitShown(conversation, "Plan done.")
            assertContains(shownConversation(conversation), "✓  Look around")
            sendPrompt("tools 3")
            awaitShown(conversation, "Searched 3 times.")
            val calls = conversation.itemsOf<AgentItem.ToolCall>()
            assertEquals(3, calls.size)
            assertTrue(calls.all { it.status == AgentToolStatus.Completed })
            assertContains(shownConversation(conversation), "Search step 2")
        }

    @Test
    fun `the place of the agent is locked once the conversation started`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("echo here")
            awaitIdle(conversation)
            awaitTag(AppTags.AGENT_MODE_WORKTREE).performClick()
            waitForIdle()
            assertEquals(WorkMode.Direct, conversation.mode)
        }

    @Test
    fun `the agent command is set in the settings`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            app.state.openSettings()
            awaitTag(AppTags.settingsCategory("Experimental")).performClick()
            val field = awaitTag(AppTags.SETTINGS_AGENT_COMMAND)
            field.performTextClearance()
            field.performTextInput("no-such-agent-43")
            awaitCondition { app.state.agentCommand == "no-such-agent-43" }
            onNodeWithTag(AppTags.SETTINGS_CLOSE).performClick()
            val conversation = startConversation(app)
            sendPrompt("echo hi")
            awaitShown(conversation, "Cannot start the agent no-such-agent-43")
        }

    @Test
    fun `a command the agent runs shows its output and exit code`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("run 3 2")
            awaitShown(conversation, "Exit code 2,")
            awaitIdle(conversation)
            val card = textOf(AppTags.AGENT_TERMINAL)
            assertContains(card, "line 3")
            assertContains(card, "failed with 2")
            assertContains(card, "Exit code 2")
            assertEquals(AgentToolStatus.Failed, conversation.itemsOf<AgentItem.ToolCall>().lastOrNull()?.status)
        }

    @Test
    fun `stopping the agent kills the command it runs`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            sendPrompt("run forever")
            // Its output shows while it runs.
            awaitShown(conversation, "tick 3")
            onNodeWithTag(AppTags.AGENT_STOP).performClick()
            awaitShown(conversation, "Killed.")
            awaitIdle(conversation)
            assertContains(textOf(AppTags.AGENT_TERMINAL), "Exit code")
        }
}
