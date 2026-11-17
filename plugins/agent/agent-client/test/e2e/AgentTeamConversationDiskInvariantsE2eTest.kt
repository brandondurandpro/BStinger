package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.DelegationStatus
import org.example.kotlineditor.state.Conversation
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.fail
import kotlin.time.Duration.Companion.minutes

/**
 * A run of a team changes what its members were asked to change, and nothing else: the other files of the project byte
 * for byte, no file left behind, the repository as it was; a file a member reads and writes back keeps its encoding,
 * its line endings, its permissions and its links ([withUnusualFiles]); a run that only reads changes nothing.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamConversationDiskInvariantsE2eTest {
    private val project = SampleProject.withUnusualFiles().apply { commitAll() }
    private val store = Files.createTempDirectory("agent-store")
    private val team = commandTeam("Crew", store, "Backend", "Tester")

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
        store.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.teamConversation(): Conversation {
        val preferences = InMemoryPreferenceStore(agentPreferences("--store", "$store").load() + globalTeams(team))
        val app = launchWithProject(project, preferences)
        val conversation = startConversation(app)
        pickTeam(team.id)
        return conversation
    }

    @Test
    fun `a run whose members write files changes only those files`() =
        runComposeUiTest {
            val conversation = teamConversation()
            val before = DiskSnapshot.take(project)
            val first = project.root.resolve("docs-backend.md")
            val second = project.root.resolve("docs-tester.md")
            sendPrompt("delegate Backend: write $first one | Tester: write $second two")
            answerMember("Backend", "allow")
            answerMember("Tester", "allow")
            awaitRuns(conversation, 1)
            assertEquals(listOf(DelegationStatus.Done, DelegationStatus.Done), conversation.delegations().map { it.status })
            assertEquals("one", first.readText())
            assertEquals("two", second.readText())
            before.assertUnchanged(project, except = setOf("docs-backend.md", "docs-tester.md"))
        }

    @Test
    fun `a file a member reads and writes back is left as it was`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            val conversation = teamConversation()
            val problems = ArrayList<String>()
            for ((index, file) in project.unusualFiles().withIndex()) {
                val before = DiskSnapshot.take(project)
                sendPrompt("delegate Backend: rewrite ${project.root.resolve(file.path)}")
                answerMember("Backend", "allow")
                awaitRuns(conversation, index + 1)
                val status = conversation.delegationOf("Backend").status
                if (status != DelegationStatus.Done) problems += "${file.path}: the task ended $status"
                val problem = runCatching { before.assertUnchanged(project) }.exceptionOrNull()?.message
                if (problem != null) problems += "${file.path} (${file.unusual}): $problem"
            }
            if (problems.isNotEmpty()) fail(problems.joinToString("\n\n"))
        }

    @Test
    fun `a run that only reads changes nothing`() =
        runComposeUiTest {
            val conversation = teamConversation()
            val before = DiskSnapshot.take(project)
            val read = project.root.resolve(project.unusualFiles().firstOrNull()?.path.orEmpty())
            sendPrompt("delegate Backend: read $read | Tester: echo looked")
            awaitRuns(conversation, 1)
            assertEquals(listOf(DelegationStatus.Done, DelegationStatus.Done), conversation.delegations().map { it.status })
            // Expanding a card reads the transcript of its member on the server, not the project.
            val backend = conversation.delegationOf("Backend")
            awaitTag(AgentTags.agentDelegationToggle(backend.id)).performClick()
            awaitTag(AgentTags.agentDelegationWork(backend.id), timeout = AGENT_TIMEOUT)
            before.assertUnchanged(project)
        }
}
