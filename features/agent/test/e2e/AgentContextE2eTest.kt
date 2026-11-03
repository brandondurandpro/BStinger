package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.TextClipboard
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The context joined to a prompt: a file mentioned with `@` and completed from the project, the lines selected in the
 * editor (Ctrl+Alt+K), an image pasted; each shows above the prompt, can be removed, and goes with the prompt.
 */
@OptIn(ExperimentalTestApi::class)
class AgentContextE2eTest {
    private val project = SampleProject.standard()
    private val app = "src/main/kotlin/App.kt"
    private val appLines = SampleProject.APP_KT.trimEnd('\n').lines().size

    @AfterTest
    fun cleanup() {
        TextClipboard.set("")
        project.delete()
    }

    @Test
    fun `an @ lists the files of the project, Enter joins one, and the agent gets its text`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences())
            val conversation = startConversation(launched)
            typePrompt("context @App")
            awaitTag(AgentTags.agentCompletion(app), timeout = AGENT_TIMEOUT)
            assertEquals(listOf(app, "src/test/kotlin/AppTest.kt"), suggestions())
            pressInPrompt(Key.Enter)
            awaitTag(AgentTags.agentAttachment("App.kt"))
            awaitNoTag(AgentTags.AGENT_COMPLETION)
            assertEquals("context ", conversation.draft)
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "Context: file App.kt ($appLines lines).")
            awaitIdle(conversation)
            awaitTag(AgentTags.agentSentAttachment("App.kt"))
            assertEquals(emptyList(), attachments())
            assertEquals(listOf("App.kt"), conversation.itemsOf<AgentItem.UserText>().single().context)
        }

    @Test
    fun `a file joined can be removed, an @ that matches nothing lists nothing, Escape closes the list`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences())
            val conversation = startConversation(launched)
            typePrompt("@Str")
            awaitTag(AgentTags.agentCompletion("src/main/kotlin/util/Strings.kt"), timeout = AGENT_TIMEOUT)
            pressInPrompt(Key.Tab)
            awaitTag(AgentTags.agentAttachment("Strings.kt"))
            awaitTag(AgentTags.agentAttachmentRemove("Strings.kt")).performClick()
            awaitNoTag(AgentTags.agentAttachment("Strings.kt"))
            typePrompt("@zzz")
            waitForIdle()
            assertEquals(emptyList(), suggestions())
            conversation.draft = ""
            typePrompt("@")
            awaitTag(AgentTags.AGENT_COMPLETION, timeout = AGENT_TIMEOUT)
            pressInPrompt(Key.Escape)
            awaitNoTag(AgentTags.AGENT_COMPLETION)
            conversation.draft = "context"
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "Context: none.")
            awaitIdle(conversation)
        }

    @Test
    fun `an image pasted joins the prompt and goes as an image`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences())
            val conversation = startConversation(launched)
            TextClipboard.setImage(ONE_PIXEL_PNG)
            typePrompt("context")
            pasteInPrompt()
            awaitTag(AgentTags.agentAttachment("image-1.png"))
            assertEquals("context", conversation.draft)
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "Context: image image/png (${ONE_PIXEL_PNG.size} bytes).")
            awaitIdle(conversation)
        }

    @Test
    fun `an agent without images says the image pasted was left out, and answers the rest`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences("--no-images"))
            val conversation = startConversation(launched)
            TextClipboard.setImage(ONE_PIXEL_PNG)
            typePrompt("context")
            pasteInPrompt()
            awaitTag(AgentTags.agentAttachment("image-1.png"))
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "image-1.png was not sent: the agent takes no images.")
            awaitShown(conversation, "Context: none.")
            awaitIdle(conversation)
        }

    @Test
    fun `Ctrl+Alt+K sends the lines selected in the editor to the prompt of the agent, the file without selection`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences())
            openFromTree(app)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveHome) } }
            editorKeys { repeat(4) { pressKey(Key.DirectionDown) } }
            editorKeys { withKeyDown(Key.ShiftLeft) { repeat(2) { pressKey(Key.DirectionDown) } } }
            sendSelectionToAgent()
            awaitTag(AgentTags.agentAttachment("App.kt:5-6"))
            val conversation = checkNotNull(launched.project.agent.current)
            assertTrue(launched.project.agentMode)
            typePrompt("context")
            pressInPrompt(Key.Enter)
            awaitShown(conversation, "Context: lines App.kt#L5-L6 (2 lines).")
            awaitIdle(conversation)
            awaitTag(AgentTags.MODE_IDE).performClick()
            awaitTag(AgentTags.MODE_AGENT)
            editorKeys { pressKey(Key.DirectionRight) }
            sendSelectionToAgent()
            awaitTag(AgentTags.agentAttachment("App.kt"))
            assertEquals(conversation, launched.project.agent.current)
        }

    @Test
    fun `in agent mode, Ctrl+Alt+K in a file of the conversation goes back to its prompt with the lines`() =
        runComposeUiTest {
            val launched = launchWithProject(project, agentPreferences())
            val conversation = startConversation(launched)
            if (!isShown(TreeTags.treeRow("src"))) awaitTag(AppTags.TOOL_PROJECT).performClick()
            awaitTag(TreeTags.treeChevron("src")).performClick()
            awaitTag(TreeTags.treeChevron("src/main")).performClick()
            awaitTag(TreeTags.treeChevron("src/main/kotlin")).performClick()
            awaitTag(TreeTags.treeRow(app)).performDoubleClick()
            awaitCondition { !conversation.showsChat }
            awaitEditorReady()
            editorKeys { withKeyDown(Key.ShiftLeft) { pressKey(Key.DirectionDown) } }
            sendSelectionToAgent()
            awaitTag(AgentTags.agentAttachment("App.kt:1"))
            assertTrue(conversation.showsChat)
            assertEquals(conversation, launched.project.agent.current)
        }
}
