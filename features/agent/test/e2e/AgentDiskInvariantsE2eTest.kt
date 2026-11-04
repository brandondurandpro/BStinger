package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.model.AgentToolStatus
import org.example.kotlineditor.state.Conversation
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.fail

/**
 * What an agent writes changes only the text it changed: a file it reads and writes back is left byte for byte, with
 * its permissions, links and encoding, and Git as it was. Run on the files real projects have ([withUnusualFiles]).
 */
@OptIn(ExperimentalTestApi::class)
class AgentDiskInvariantsE2eTest {
    private val project = SampleProject.withUnusualFiles().apply { commitAll() }

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
    }

    // Has the agent run [prompt], allowing its write, and waits for the end of its turn; fails, with what the
    // conversation shows, unless the write went through.
    private fun ComposeUiTest.agentWrites(conversation: Conversation, prompt: String) {
        val writes = conversation.itemsOf<AgentItem.ToolCall>().count { it.status == AgentToolStatus.Completed }
        sendPrompt(prompt)
        answerPermission("allow")
        awaitIdle(conversation)
        val written = conversation.itemsOf<AgentItem.ToolCall>().count { it.status == AgentToolStatus.Completed }
        if (written == writes) fail("'$prompt' wrote nothing; the conversation shows:\n${shownConversation(conversation)}")
    }

    @Test
    fun `a file the agent reads and writes back is left as it was`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val problems = ArrayList<String>()
            for (file in project.unusualFiles()) {
                val before = DiskSnapshot.take(project)
                agentWrites(conversation, "rewrite ${project.root.resolve(file.path)}")
                val problem = runCatching { before.assertUnchanged(project) }.exceptionOrNull()?.message
                if (problem != null) problems += "${file.path} (${file.unusual}): $problem"
            }
            if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
        }

    @Test
    fun `a file in ISO-8859-1 that the agent edits stays in ISO-8859-1`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            val file = project.root.resolve("unusual/latin1.php")
            agentWrites(conversation, "write $file <?php // été")
            assertContentEquals("<?php // été".toByteArray(Charsets.ISO_8859_1), Files.readAllBytes(file))
        }
}
