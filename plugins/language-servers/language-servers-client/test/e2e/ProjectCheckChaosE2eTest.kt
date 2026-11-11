package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.LanguageServerTags
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** The check of the project when the network or the IDE server fails. */
@OptIn(ExperimentalTestApi::class)
class ProjectCheckChaosE2eTest {
    private val project = FakePhpServers.webProject()
    private val runs = AtomicInteger()
    private val servers =
        FakePhpServers(
            check = {
                runs.incrementAndGet()
                problems(project, 1)
            },
        )

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `a check whose answer is lost can be asked again, and shows its result`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/app.ts")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(LanguageServerTags.statusLanguageServer(running.id)).performClick()
            awaitTag(LanguageServerTags.LANGUAGE_SERVERS)
            app.network.dropFromServer = true
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            awaitCondition { runs.get() >= 1 }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            awaitCondition { textOf(LanguageServerTags.PROJECT_CHECK_SUMMARY) == "1 problems in 1 files" }
            assertTrue(runs.get() >= 1, "the check ran")
        }

    @Test
    fun `the network down during a check does not leave the button stuck`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
            openFromTree("src/app.ts")
            val running = awaitServer(app) { it.state == LanguageServerState.Running }
            awaitTag(LanguageServerTags.statusLanguageServer(running.id)).performClick()
            awaitTag(LanguageServerTags.LANGUAGE_SERVERS)
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            app.network.comeBack()
            awaitReconnected()
            onNodeWithTag(LanguageServerTags.PROJECT_CHECK).performClick()
            awaitCondition { textOf(LanguageServerTags.PROJECT_CHECK_SUMMARY) == "1 problems in 1 files" }
        }
}
