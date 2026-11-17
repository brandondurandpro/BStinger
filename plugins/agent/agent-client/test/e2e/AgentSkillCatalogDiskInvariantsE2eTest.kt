package org.example.kotlineditor.e2e

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

private const val TDD = "skills/engineering/tdd"

/**
 * Browsing the skill catalog and installing a skill change nothing in the project or its repository, on the files real
 * projects have: the server writes only under `skill-catalog` (what it downloaded) and `skills` (what was installed)
 * of its data directory, and leaves no temporary file there.
 */
@OptIn(ExperimentalTestApi::class)
class AgentSkillCatalogDiskInvariantsE2eTest {
    private val project = SampleProject.withUnusualFiles().apply { commitAll() }
    private val servers = TeamRegistry()
    private val repositories = SkillRepositories()

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
        servers.close()
        repositories.close()
    }

    private fun namesIn(directory: File): Set<String> = directory.walkTopDown().map { it.relativeTo(directory).path }.toSet()

    @Test
    fun `listing and installing change nothing in the project, and write only the catalog and the skills in the data directory`() =
        runComposeUiTest {
            repositories.addPopular()
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings(repositories, CATALOG_SOURCES))
            startConversation(app)
            val data = app.process.dataDirectory
            val before = DiskSnapshot.take(project)
            val dataBefore = namesIn(data)
            openCatalog()
            awaitTag(AgentTags.agentCatalogInstall(MATT, TDD), AGENT_TIMEOUT).performClick()
            awaitTag(AgentTags.agentCatalogInstalled(MATT, TDD), AGENT_TIMEOUT)
            awaitTag(AgentTags.AGENT_CATALOG_REFRESH).performClick()
            awaitTag(AgentTags.agentCatalogInstalled(MATT, TDD), AGENT_TIMEOUT)
            before.assertUnchanged(project)
            val added = namesIn(data) - dataBefore
            val outside = added.filter { !it.startsWith("skill-catalog") && !it.startsWith("skills") }
            assertEquals(emptyList(), outside, "written outside the catalog and the skills")
            assertEquals(emptyList(), added.filter { it.contains(".download-") || it.contains(".installing-") }, "temporary files left")
            assertTrue(File(data, "skills/mattpocock-skills/engineering--tdd/SKILL.md").isFile)
        }
}
