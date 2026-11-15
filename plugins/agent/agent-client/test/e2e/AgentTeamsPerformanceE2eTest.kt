package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

private const val MODELS = 500
private const val COMMANDS = 500

/** The menus of the member of a team, on an agent that offers hundreds of models and commands. */
@OptIn(ExperimentalTestApi::class)
class AgentTeamsPerformanceE2eTest {
    private val project = SampleProject.standard()
    private val store = Files.createTempDirectory("agent-store")

    @AfterTest
    fun cleanup() {
        project.delete()
        store.toFile().deleteRecursively()
    }

    @Test
    fun `the model and skills menus open and filter quickly on hundreds of values`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store", "--models", "$MODELS", "--commands", "$COMMANDS"))
            startConversation(app)
            openNewTeam()
            val model = AgentTags.AGENT_MEMBER_MODEL
            val skills = AgentTags.AGENT_MEMBER_SKILLS
            measure("open the model menu on $MODELS models", budgetMillis = 8_000, budgetMegabytes = 400) {
                openSelect(model)
                awaitTag("$model.choice.Smart", timeout = AGENT_TIMEOUT)
                renderFrame()
            }
            measure("filter $MODELS models", budgetMillis = 5_000, budgetMegabytes = 300) {
                awaitTag("$model.filter").performTextReplacement("Extra 499")
                awaitNoTag("$model.choice.Smart")
                awaitTag("$model.choice.Extra 499")
                renderFrame()
            }
            escapeSelect(model)
            measure("open the skills menu on $COMMANDS commands", budgetMillis = 8_000, budgetMegabytes = 400) {
                openSelect(skills)
                awaitTag("$skills.choice.extra-500", timeout = AGENT_TIMEOUT)
                renderFrame()
            }
            measure("toggle a skill among $COMMANDS", budgetMillis = 5_000, budgetMegabytes = 300) {
                awaitTag("$skills.choice.extra-500").performScrollTo().performClick()
                awaitTag(AgentTags.agentMemberSkill("extra-500"))
                renderFrame()
            }
        }

    @Test
    fun `a team of a hundred members opens and adds a member quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, agentPreferences("--store", "$store"))
            startConversation(app)
            openNewTeam()
            measure("add 100 members", budgetMillis = 30_000, budgetMegabytes = 800) {
                repeat(100) { awaitTag(AgentTags.AGENT_TEAM_ADD_MEMBER).performClick() }
                awaitTag(AgentTags.agentTeamMember(100))
                renderFrame()
            }
        }
}
