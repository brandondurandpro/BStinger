package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Working in another folder of the server changes that folder only by what the agent writes there: the project of the
 * window stays byte for byte, the other folder too (the unusual files included) when the agent only reads, no file is left
 * behind, and neither repository moves.
 */
@OptIn(ExperimentalTestApi::class)
class AgentOtherFolderDiskInvariantsE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject.withUnusualFiles()

    @AfterTest
    fun cleanup() {
        other.unlockUnusualFiles()
        project.delete()
        other.delete()
    }

    @Test
    fun `choosing another folder and talking in it changes nothing in either project`() =
        runComposeUiTest {
            project.commitAll()
            other.commitAll()
            val inProject = DiskSnapshot.take(project)
            val inOther = DiskSnapshot.take(other)
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped(other.path)
            awaitCondition { conversation.folder == other.path }
            // The folder is a repository: the question of the branch comes, the folder's own branch is taken.
            awaitTag(AgentTags.AGENT_BASE_PICKER)
            awaitTag(AgentTags.AGENT_BASE_PROJECT).performClick()
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${other.path}")
            awaitIdle(conversation)
            inProject.assertUnchanged(project)
            inOther.assertUnchanged(other)
        }

    @Test
    fun `a file the agent writes in the other folder is the only change, in that folder`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            project.commitAll()
            other.commitAll()
            val inProject = DiskSnapshot.take(project)
            val inOther = DiskSnapshot.take(other)
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped(other.path)
            awaitCondition { conversation.folder == other.path }
            // The folder is a repository: the question of the branch comes, the folder's own branch is taken.
            awaitTag(AgentTags.AGENT_BASE_PICKER)
            awaitTag(AgentTags.AGENT_BASE_PROJECT).performClick()
            awaitNoTag(AgentTags.AGENT_BASE_PICKER)
            val written = other.root.resolve("written-by-agent.md")
            sendPrompt("write $written hello")
            answerPermission("allow")
            awaitAnswer(conversation, "Wrote", AGENT_TIMEOUT)
            awaitIdle(conversation)
            assertEquals("hello", written.readText())
            inProject.assertUnchanged(project)
            inOther.assertUnchanged(other, except = setOf("written-by-agent.md"))
        }
}
