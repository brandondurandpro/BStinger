package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Choosing the folder of a conversation reads the project and writes nothing: the files byte for byte (the unusual ones
 * too), no file left behind, the repository as it was, in the project and in the worktree of the conversation.
 */
@OptIn(ExperimentalTestApi::class)
class AgentFolderDiskInvariantsE2eTest {
    private val project = SampleProject.withUnusualFiles()

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
    }

    @Test
    fun `choosing a folder and working in it changes nothing on the disk`() =
        runComposeUiTest {
            project.file("backend/api/Main.kt", "fun main() = Unit\n")
            project.commitAll()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("backend/api")
            awaitCondition { conversation.subdirectory == "backend/api" }
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${project.path}/backend/api")
            awaitIdle(conversation)
            before.assertUnchanged(project)
        }

    @Test
    fun `browsing the folders and giving up changes nothing`() =
        runComposeUiTest {
            project.commitAll()
            val before = DiskSnapshot.take(project)
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            openFolderMenu()
            typeFolder("${project.path}/missing")
            submitFolder()
            awaitTag(org.example.kotlineditor.ui.AgentTags.AGENT_FOLDER_ERROR)
            assertEquals("", conversation.subdirectory)
            before.assertUnchanged(project)
        }
}
