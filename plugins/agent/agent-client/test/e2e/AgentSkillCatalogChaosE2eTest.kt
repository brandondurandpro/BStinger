package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.testkit.skills.SkillRepositories
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val TDD = "skills/engineering/tdd"

/**
 * What the skill catalog does when the network or the server fails: it lists once the link is back, a cut that comes
 * after the server installed a skill installs it once, and the repositories the server downloaded stay usable offline
 * after it restarts.
 */
@OptIn(ExperimentalTestApi::class)
class AgentSkillCatalogChaosE2eTest {
    private val project = SampleProject.standard()
    private val servers = TeamRegistry()
    private val repositories = SkillRepositories()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.close()
        repositories.close()
    }

    private fun ComposeUiTest.launch() =
        launchWithProject(project, teamPreferences(), chaos = true, agents = servers.settings(repositories, CATALOG_SOURCES))

    @Test
    fun `the catalog asked while the network is down is listed once it is back, without downloading twice`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            app.network.goDown()
            awaitReconnecting()
            openCatalog()
            app.network.comeBack()
            awaitReconnected()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            assertEquals(3, repositories.requests, "one download per repository")
        }

    @Test
    fun `an install cut after the server ran it is installed once`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogInstall(MATT, TDD), AGENT_TIMEOUT)
            app.network.dropFromServer = true
            awaitTag(AgentTags.agentCatalogInstall(MATT, TDD)).performClick()
            val folder = File(app.process.dataDirectory, "skills/mattpocock-skills/engineering--tdd")
            awaitCondition(AGENT_TIMEOUT) { File(folder, "SKILL.md").isFile }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitTag(AgentTags.agentCatalogInstalled(MATT, TDD), AGENT_TIMEOUT)
            assertEquals(3, repositories.requests, "the retry reads what the server kept")
            assertTrue(File(folder, "tests.md").isFile)
            assertEquals(1, File(app.process.dataDirectory, "skills/mattpocock-skills").listFiles { it.isDirectory }.orEmpty().size)
        }

    @Test
    fun `after the server restarts a skill installs from the repositories it downloaded, with no network`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            openCatalog()
            awaitTag(AgentTags.agentCatalogInstall(MATT, TDD), AGENT_TIMEOUT)
            repositories.close()
            app.process.restart()
            awaitReconnected()
            awaitTag(AgentTags.agentCatalogInstall(MATT, TDD)).performClick()
            awaitTag(AgentTags.agentCatalogInstalled(MATT, TDD), AGENT_TIMEOUT)
            assertTrue(File(app.process.dataDirectory, "skills/mattpocock-skills/engineering--tdd/SKILL.md").isFile)
        }

    @Test
    fun `on a slow link the catalog stays usable and the answers come in`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            repositories.addPopular()
            val app = launch()
            startConversation(app)
            app.network.latencyMs = 400
            openCatalog()
            awaitTag(AgentTags.agentCatalogSkill(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.agentCatalogInstall(ANTHROPIC, "skills/pdf")).performClick()
            awaitTag(AgentTags.agentCatalogInstalled(ANTHROPIC, "skills/pdf"), AGENT_TIMEOUT)
        }
}
