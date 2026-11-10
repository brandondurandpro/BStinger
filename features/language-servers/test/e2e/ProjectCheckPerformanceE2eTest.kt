package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test

private const val FILES_WITH_PROBLEMS = 2_000
private const val PROBLEMS_PER_FILE = 5

/** The result of a check of thousands of problems must not slow the window. */
@OptIn(ExperimentalTestApi::class)
class ProjectCheckPerformanceE2eTest {
    private val project = FakePhpServers.webProject()
    private val servers = FakePhpServers(check = { problems(project, FILES_WITH_PROBLEMS, PROBLEMS_PER_FILE) })

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `thousands of problems are listed quickly, only the visible ones are composed`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/app.ts")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(LanguageServerTags.statusLanguageServer(running.id)).performClick()
            awaitTag(LanguageServerTags.LANGUAGE_SERVERS)
            val label = "list ${FILES_WITH_PROBLEMS * PROBLEMS_PER_FILE} problems of a project check"
            measure(label, budgetMillis = 8_000, budgetMegabytes = 400) {
                onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
                awaitTag(LanguageServerTags.PROJECT_CHECK_PROBLEMS)
                renderFrame()
            }
        }
}
