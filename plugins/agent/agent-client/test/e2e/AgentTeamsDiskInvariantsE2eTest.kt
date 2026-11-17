package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AgentTags
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Opening the Teams window, reading the models of a provider, listing the skills and making a team change nothing in
 * the project or its repository (teams are the IDE's own data, kept in its preferences), and leave nothing in the data
 * directory of the server (listing the skills creates no `skills` folder there). Run on the files real projects have.
 */
@OptIn(ExperimentalTestApi::class)
class AgentTeamsDiskInvariantsE2eTest {
    private val project =
        SampleProject.withUnusualFiles().apply {
            file(".claude/skills/review/SKILL.md", "---\nname: Code review\ndescription: Reviews a diff\n---\nBody\n")
            commitAll()
        }
    private val servers = TeamRegistry()

    @AfterTest
    fun cleanup() {
        project.unlockUnusualFiles()
        project.delete()
        servers.close()
    }

    private fun namesIn(directory: File): Set<String> =
        directory.walkTopDown().map { it.relativeTo(directory).path }.toSet()

    @Test
    fun `opening the window, listing the skills and making a team change nothing on disk`() =
        runComposeUiTest {
            val app = launchWithProject(project, teamPreferences(), agents = servers.settings())
            startConversation(app)
            val data = app.process.dataDirectory
            val before = DiskSnapshot.take(project)
            val dataBefore = namesIn(data)
            openTeams()
            awaitTag(AgentTags.AGENT_TEAM_NEW).performClick()
            awaitField(AgentTags.TEAM_MANAGER, "model", AGENT_TIMEOUT)
            val review = project.root.resolve(".claude/skills/review").toString()
            val row = AgentTags.agentTeamValue(AgentTags.TEAM_MANAGER, "skills", review)
            awaitField(AgentTags.TEAM_MANAGER, "skills").performClick()
            awaitTag(row, AGENT_TIMEOUT).performClick()
            awaitTag(row).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(row)
            awaitTag(AgentTags.AGENT_TEAM_NAME).performTextInput("Reviewers")
            saveTeam()
            awaitCondition { app.project.agent.teams.teams.size == 1 }
            awaitTag(AgentTags.AGENT_TEAMS_CLOSE).performClick()
            awaitNoTag(AgentTags.AGENT_TEAMS_DIALOG)
            before.assertUnchanged(project)
            assertFalse(File(data, "skills").exists(), "The data directory got a skills folder")
            assertEquals(dataBefore, namesIn(data))
        }
}
