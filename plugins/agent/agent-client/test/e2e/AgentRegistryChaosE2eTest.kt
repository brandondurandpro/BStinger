package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.acp.registryPlatformForTests
import org.example.kotlineditor.model.AgentItem
import org.example.kotlineditor.state.InMemoryPreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.testkit.acp.FakeRegistry
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The agents of the registry when the registry fails: down when the settings read it, down when the server downloads
 * the agent; the user sees why, nothing half-installed runs, and the next prompt works once it is back.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRegistryChaosE2eTest {
    private val project = SampleProject.standard()
    private val registry = FakeRegistry(registryPlatformForTests())
    private val data = Files.createTempDirectory("agent-data").toFile()
    private val preferences =
        InMemoryPreferenceStore(
            mapOf(PreferenceKeys.AGENT_MODE to "true", PreferenceKeys.AGENT_REGISTRY to "fake-agent"),
        )

    @AfterTest
    fun cleanup() {
        project.delete()
        registry.close()
        data.deleteRecursively()
    }

    @Test
    fun `a registry that is down is explained in the settings`() =
        runComposeUiTest {
            registry.down = true
            val app = launchWithProject(project, preferences, agents = agentSettings(registry.url, data))
            app.state.settings.open()
            awaitTag(AppTags.settingsCategory("Experimental")).performClick()
            awaitCondition { "The registry could not be read" in textOf(AppTags.setting("Agent")) }
        }

    @Test
    fun `a download that fails is explained, and the next prompt installs the agent once it is back`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences, agents = agentSettings(registry.url, data))
            // Down before agent mode lists the history, which would install the agent.
            registry.down = true
            val conversation = startConversation(app)
            sendPrompt("echo first")
            awaitCondition(AGENT_TIMEOUT) { conversation.itemsOf<AgentItem.Notice>().isNotEmpty() }
            awaitIdle(conversation)
            assertContains(conversation.itemsOf<AgentItem.Notice>().joinToString { it.text }, "503")
            registry.down = false
            sendPrompt("echo second")
            awaitAnswer(conversation, "second")
            awaitIdle(conversation)
            assertEquals(1, registry.downloads.get())
        }
}
