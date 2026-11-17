package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/** The folder of a conversation when the link to the server or the disk under it fails. */
@OptIn(ExperimentalTestApi::class, kotlin.io.path.ExperimentalPathApi::class)
class AgentFolderChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun folders() {
        project.file("backend/api/Main.kt", "fun main() = Unit\n")
        project.file("docs/readme.md", "# docs\n")
    }

    @Test
    fun `a folder chosen while the network is down is chosen once the link is back, and never twice`() =
        runComposeUiTest {
            folders()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            openFolderMenu()
            app.network.goDown()
            awaitReconnecting()
            typeFolder("docs")
            submitFolder()
            app.network.comeBack()
            awaitReconnected()
            // Whether the request waited for the link or failed, the user can say it again: it ends chosen, not stuck.
            awaitCondition(AGENT_TIMEOUT) {
                if (conversation.subdirectory != "docs") submitFolder()
                conversation.subdirectory == "docs"
            }
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${project.path}/docs")
        }

    @Test
    fun `a server that restarts leaves the picker usable, and the agent starts in the folder chosen`() =
        runComposeUiTest {
            folders()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            chooseFolderTyped("backend")
            awaitCondition { conversation.subdirectory == "backend" }
            app.process.restart()
            awaitReconnected()
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${project.path}/backend", AGENT_TIMEOUT)
            assertEquals("backend", conversation.subdirectory)
        }

    @Test
    fun `a folder that another program deleted is refused, and the folder stays as it was`() =
        runComposeUiTest {
            folders()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            val conversation = startConversation(app)
            openFolderMenu()
            val docs = project.root.resolve("docs")
            awaitTag(AgentTags.agentFolderChoice(docs.toString()))
            docs.deleteRecursively()
            typeFolder("docs")
            submitFolder()
            awaitTag(AgentTags.AGENT_FOLDER_ERROR)
            assertContains(textOf(AgentTags.AGENT_FOLDER_ERROR), "is not a folder")
            assertEquals("", conversation.subdirectory)
        }

    @Test
    fun `on a slow link the list shows the last thing typed, not an earlier answer`() =
        runComposeUiTest {
            folders()
            val app = launchWithProject(project, agentPreferences(), chaos = true)
            startConversation(app)
            openFolderMenu()
            app.network.latencyMs = 400
            val backend = project.root.resolve("backend").toString()
            val docs = project.root.resolve("docs").toString()
            typeFolder("do")
            typeFolder("ba")
            app.network.latencyMs = 0
            awaitTag(AgentTags.agentFolderChoice(backend), timeout = AGENT_TIMEOUT)
            awaitNoTag(AgentTags.agentFolderChoice(docs))
        }
}
