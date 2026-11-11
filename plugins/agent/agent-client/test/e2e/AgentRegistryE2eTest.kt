package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.acp.registryPlatformForTests
import org.example.kotlineditor.testkit.acp.FakeRegistry
import org.example.kotlineditor.ui.AgentTags
import org.example.kotlineditor.ui.AppTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals

/**
 * The agents of the ACP registry (a registry on loopback): chosen in the settings, installed by the server on the
 * first prompt, and the agents the server cannot run shown with the reason.
 */
@OptIn(ExperimentalTestApi::class)
class AgentRegistryE2eTest {
    private val project = SampleProject.standard()
    private val registry = FakeRegistry(registryPlatformForTests())
    private val data = Files.createTempDirectory("agent-data").toFile()

    @AfterTest
    fun cleanup() {
        project.delete()
        registry.close()
        data.deleteRecursively()
    }

    @Test
    fun `an agent chosen from the registry is installed by the server and answers`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agentSettings(registry.url, data))
            app.state.settings.open()
            awaitTag(AppTags.settingsCategory("Experimental")).performClick()
            awaitTag(AgentTags.settingsAgent("fake-agent")).performClick()
            // A command line would win over the agent chosen: choosing one clears it.
            awaitCondition { app.state.settings.agentRegistry == "fake-agent" && app.state.settings.agentCommand.isEmpty() }
            onNodeWithTag(AppTags.SETTINGS_CLOSE).performClick()
            val conversation = startConversation(app)
            sendPrompt("echo from the registry")
            awaitAnswer(conversation, "from the registry")
            awaitIdle(conversation)
            assertEquals(1, registry.downloads.get())
        }

    @Test
    fun `an agent the server cannot run says why, and cannot be chosen`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences(), agents = agentSettings(registry.url, data))
            app.state.settings.open()
            awaitTag(AppTags.settingsCategory("Experimental")).performClick()
            val row = awaitTag(AgentTags.settingsAgent("unsigned-agent"))
            assertContains(textOf(AgentTags.settingsAgent("unsigned-agent")), "no checksum")
            row.performClick()
            waitForIdle()
            assertEquals("", app.state.settings.agentRegistry)
        }
}
