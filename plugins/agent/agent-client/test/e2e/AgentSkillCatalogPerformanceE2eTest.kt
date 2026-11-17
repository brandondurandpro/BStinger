package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.testkit.skills.SkillRepositories
import org.example.kotlineditor.ui.AgentTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

private const val SKILLS = 3_000
private const val MAX_COMPOSED_ROWS = 60

/**
 * Performance of the skill catalog on a repository of [SKILLS] skills: downloading and unpacking it, listing it,
 * narrowing the list and installing one. The list is virtualized, so only the visible rows are composed.
 */
@OptIn(ExperimentalTestApi::class)
class AgentSkillCatalogPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val servers = TeamRegistry()
    private val repositories = SkillRepositories()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.close()
        repositories.close()
    }

    private fun path(index: Int) = "skills/group-${index % 20}/skill-${index.toString().padStart(4, '0')}"

    @Test
    fun `thousands of skills are listed, narrowed and installed fast, and only the visible rows are composed`() =
        runComposeUiTest(testTimeout = 5.minutes) {
            val files = HashMap<String, String>()
            repeat(SKILLS) { files += repositories.skill(MATT, path(it), "skill-$it", "Description of the skill number $it") }
            repositories.add(MATT, files)
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings(repositories, listOf(MATT)))
            startConversation(app)
            measure("download and list $SKILLS skills", budgetMillis = 10_000, budgetMegabytes = 800) {
                openCatalog()
                awaitTag(AgentTags.AGENT_CATALOG_LIST, timeout = AGENT_TIMEOUT)
                awaitTag(AgentTags.agentCatalogSource(MATT), timeout = AGENT_TIMEOUT)
                renderFrame()
            }
            val composed = onAllNodes(hasTestTagPrefix("agent.catalog.skill:")).fetchSemanticsNodes().size
            assertTrue(composed in 1 until MAX_COMPOSED_ROWS, "composed rows: $composed")
            measure("narrow $SKILLS skills", budgetMillis = 3_000, budgetMegabytes = 100) {
                awaitTag(AgentTags.AGENT_CATALOG_FILTER).performTextInput("number 2999")
                awaitTag(AgentTags.agentCatalogSkill(MATT, path(2_999)), timeout = AGENT_TIMEOUT)
                awaitNoTag(AgentTags.agentCatalogSkill(MATT, path(0)), timeout = AGENT_TIMEOUT)
                renderFrame()
            }
            measure("install one of $SKILLS skills", budgetMillis = 2_000, budgetMegabytes = 50) {
                awaitTag(AgentTags.agentCatalogInstall(MATT, path(2_999))).performClick()
                awaitTag(AgentTags.agentCatalogInstalled(MATT, path(2_999)), timeout = AGENT_TIMEOUT)
                renderFrame()
            }
        }
}
