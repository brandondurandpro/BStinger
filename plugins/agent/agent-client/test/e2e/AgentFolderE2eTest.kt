package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.WorkMode
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.RemoteTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The folder a new conversation works in: the root of the project, a folder under it, or any folder of the server. */
@OptIn(ExperimentalTestApi::class)
class AgentFolderE2eTest {
    private val project = SampleProject.standard()
    private val other = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
        other.delete()
    }

    private fun foldersOfProject() {
        project.file("backend/api/Main.kt", "fun main() = Unit\n")
        project.file("docs/readme.md", "# docs\n")
        project.file(".hidden/secret.txt", "secret\n")
    }

    @Test
    fun `a conversation works in the project root until another folder is chosen`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            assertContains(textOf(AgentTags.AGENT_FOLDER), "Project root")
            assertEquals("", conversation.subdirectory)
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${project.path}")
        }

    @Test
    fun `a folder typed under the project is where the agent works`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("backend/api")
            awaitNoTag(AgentTags.AGENT_FOLDER_PATH)
            assertEquals("backend/api", conversation.subdirectory)
            assertContains(textOf(AgentTags.AGENT_FOLDER), "backend/api")
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${project.path}/backend/api")
        }

    @Test
    fun `an absolute path under the project is the same folder as the relative one`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("${project.path}/docs")
            awaitNoTag(AgentTags.AGENT_FOLDER_PATH)
            assertEquals("docs", conversation.subdirectory)
        }

    @Test
    fun `the folders of the project are listed, the hidden ones apart, and a click goes into one`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            openFolderMenu()
            val backend = project.root.resolve("backend").toString()
            val docs = project.root.resolve("docs").toString()
            val hidden = project.root.resolve(".hidden").toString()
            awaitTag(AgentTags.agentFolderChoice(backend))
            awaitTag(AgentTags.agentFolderChoice(docs))
            assertTrue(onAllNodes(hasTestTag(AgentTags.agentFolderChoice(hidden))).fetchSemanticsNodes().isEmpty())
            // The beginning of a name narrows the list.
            typeFolder("do")
            awaitNoTag(AgentTags.agentFolderChoice(backend))
            awaitTag(AgentTags.agentFolderChoice(docs))
            // A click goes into the folder, and the button applies it.
            awaitTag(AgentTags.agentFolderChoice(docs)).performClick()
            awaitTag(AgentTags.AGENT_FOLDER_APPLY).performClick()
            awaitNoTag(AgentTags.AGENT_FOLDER_PATH)
            assertEquals("docs", conversation.subdirectory)
        }

    @Test
    fun `a folder that does not exist is refused, and nothing changes`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("backend/missing")
            awaitTag(AgentTags.AGENT_FOLDER_ERROR)
            assertContains(textOf(AgentTags.AGENT_FOLDER_ERROR), "is not a folder")
            assertEquals("", conversation.subdirectory)
            // Typing again takes the message away.
            typeFolder("backend")
            awaitNoTag(AgentTags.AGENT_FOLDER_ERROR)
        }

    @Test
    fun `a file is no folder`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("backend/api/Main.kt")
            awaitTag(AgentTags.AGENT_FOLDER_ERROR)
            assertEquals("", conversation.subdirectory)
        }

    @Test
    fun `the project root brings the agent back from a folder`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("docs")
            awaitCondition { conversation.subdirectory == "docs" }
            openFolderMenu()
            awaitTag(AgentTags.AGENT_FOLDER_ROOT).performClick()
            awaitNoTag(AgentTags.AGENT_FOLDER_PATH)
            assertEquals("", conversation.subdirectory)
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${project.path}")
        }

    @Test
    fun `escape closes the menu without choosing`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            openFolderMenu()
            typeFolder("docs")
            awaitTag(AgentTags.AGENT_FOLDER_PATH).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AgentTags.AGENT_FOLDER_PATH)
            assertEquals("", conversation.subdirectory)
        }

    @Test
    fun `the folder is fixed once the conversation has started`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("docs")
            awaitCondition { conversation.subdirectory == "docs" }
            sendPrompt("echo hi")
            awaitAnswer(conversation, "hi")
            awaitIdle(conversation)
            awaitTag(AgentTags.AGENT_FOLDER).assertIsNotEnabled()
            assertContains(textOf(AgentTags.AGENT_FOLDER), "docs")
        }

    @Test
    fun `each conversation has its own folder`() =
        runComposeUiTest {
            foldersOfProject()
            val app = launchWithProject(project, agentPreferences())
            val first = startConversation(app)
            chooseFolderTyped("backend")
            awaitCondition { first.subdirectory == "backend" }
            val second = startAnotherConversation(app)
            assertEquals("", second.subdirectory)
            assertContains(textOf(AgentTags.AGENT_FOLDER), "Project root")
            sendPrompt("cwd")
            awaitAnswer(second, "cwd: ${project.path}")
            assertEquals("backend", first.subdirectory)
        }

    @Test
    fun `in a worktree the agent works in the same folder of the worktree`() =
        runComposeUiTest {
            foldersOfProject()
            project.commitAll()
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped("backend/api")
            awaitCondition { conversation.subdirectory == "backend/api" }
            awaitTag(AgentTags.AGENT_MODE_WORKTREE).performClick()
            awaitCondition(AGENT_TIMEOUT) {
                val workspace = conversation.workspace
                conversation.mode == WorkMode.Worktree && !conversation.preparingWorktree && workspace != null && workspace !== app.project
            }
            val worktree = checkNotNull(conversation.workspace).root.path
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: $worktree/backend/api")
        }

    @Test
    fun `a folder outside the project is where the conversation works, in this window, with the files and branch of that folder`() =
        runComposeUiTest {
            other.file("lib/Lib.kt", "class Lib\n")
            val app = launchWithProject(project, agentPreferences())
            var windows = 0
            app.state.onNewWindow = { windows++ }
            val conversation = startConversation(app)
            chooseFolderTyped(other.path)
            awaitCondition { conversation.folder == other.path }
            assertEquals(0, windows, "no window opens")
            assertEquals("", conversation.subdirectory)
            assertEquals(project.path, app.project.root.path, "this window keeps its project")
            awaitCondition(AGENT_TIMEOUT) { conversation.workspace?.root?.path == other.path }
            awaitTag(AgentTags.agentFolderGroup(other.path))
            assertContains(textOf(AgentTags.AGENT_FOLDER), "Project root")
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${other.path}")
            // The field now leads in that folder: a folder under it is a subfolder of the conversation.
            awaitIdle(conversation)
        }

    @Test
    fun `a subfolder is chosen under the folder the conversation moved to, not under the project`() =
        runComposeUiTest {
            other.file("lib/Lib.kt", "class Lib\n")
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            chooseFolderTyped(other.path)
            awaitCondition { conversation.folder == other.path }
            chooseFolderTyped("lib")
            awaitCondition { conversation.subdirectory == "lib" }
            sendPrompt("cwd")
            awaitAnswer(conversation, "cwd: ${other.path}/lib")
        }

    @Test
    fun `Browse in the menu opens the Open Project dialog, and the folder chosen there is the conversation's`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences())
            val conversation = startConversation(app)
            openFolderMenu()
            awaitTag(AgentTags.AGENT_FOLDER_BROWSE).performClick()
            awaitTag(RemoteTags.OPEN_PROJECT)
            showDirectoryInProjectPicker(other.path)
            awaitCondition { runCatching { onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).assertIsEnabled() }.isSuccess }
            onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).performClick()
            awaitNoTag(RemoteTags.OPEN_PROJECT)
            awaitCondition { conversation.folder == other.path }
            assertEquals(project.path, app.project.root.path, "no project opened")
        }

    @Test
    fun `Browse from a conversation without a project moves it to the folder chosen in the dialog`() =
        runComposeUiTest {
            other.commitAll()
            val app = launchWithProject(project, agentPreferences())
            startConversation(app)
            val conversation = clickNewConversationWithoutProject(app)
            openFolderMenu()
            awaitTag(AgentTags.AGENT_FOLDER_BROWSE).performClick()
            awaitTag(RemoteTags.OPEN_PROJECT)
            showDirectoryInProjectPicker(other.path)
            awaitCondition { runCatching { onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).assertIsEnabled() }.isSuccess }
            onNodeWithTag(RemoteTags.OPEN_PROJECT_OPEN).performClick()
            awaitNoTag(RemoteTags.OPEN_PROJECT)
            awaitCondition(AGENT_TIMEOUT) { conversation.folder == other.path && !conversation.scratch }
            awaitTag(AgentTags.AGENT_BASE_PICKER)
        }

    @Test
    fun `a conversation started in a subfolder is in the history after a restart, working in that folder`() =
        runComposeUiTest {
            foldersOfProject()
            val store = java.nio.file.Files.createTempDirectory("agent-store")
            try {
                val folder = "${project.path}/backend/api"
                val session = pastSession(store, folder, listOf("echo in api" to "in api "))
                val app = launchWithProject(project, agentPreferences("--store", "$store"))
                val conversation = awaitHistory(app, session)
                assertEquals("backend/api", conversation.subdirectory)
                assertEquals(null, conversation.folder)
                awaitTag(AgentTags.agentConversation(conversation.id)).performClick()
                awaitShown(conversation, "in api")
                assertContains(textOf(AgentTags.AGENT_FOLDER), "backend/api")
            } finally {
                store.toFile().deleteRecursively()
            }
        }
}
