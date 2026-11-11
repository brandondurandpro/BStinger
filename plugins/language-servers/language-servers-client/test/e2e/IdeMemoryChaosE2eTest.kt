package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The memory shown when the network or the IDE server fails. */
@OptIn(ExperimentalTestApi::class)
class IdeMemoryChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `while the network is down the UI's memory still follows, and the server's is the last known`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            val ui = SeparateUiProcess(residentBytes = 300 * MEGABYTE)
            app.runUiAs(ui)
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 300 MB" }
            app.network.goDown()
            awaitReconnecting()
            val serverBefore = textOf(LanguageServerTags.STATUS_SERVER_MEMORY)
            ui.residentBytes = 400 * MEGABYTE
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 400 MB" }
            assertEquals(serverBefore, textOf(LanguageServerTags.STATUS_SERVER_MEMORY))
            awaitCondition { textOf(LanguageServerTags.STATUS_TOTAL_MEMORY) == "Total ${shownTotal(app)}" }
            app.network.comeBack()
            awaitReconnected()
            awaitTag(LanguageServerTags.STATUS_SERVER_MEMORY)
        }

    @Test
    fun `a restarted server is measured again`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.runUiAs(SeparateUiProcess(residentBytes = 300 * MEGABYTE))
            awaitTag(LanguageServerTags.STATUS_SERVER_MEMORY)
            val state = app.state.session.languageServers
            val before = state.status
            app.process.restart()
            // The new server numbers its status from the start: the client takes it all the same.
            awaitCondition { state.status !== before && state.status.server.residentBytes > 0 }
            awaitCondition {
                val server = checkNotNull(state.memory.serverBytes)
                textOf(LanguageServerTags.STATUS_SERVER_MEMORY).startsWith("Server ") &&
                    textOf(LanguageServerTags.STATUS_TOTAL_MEMORY) == "Total ${shownTotal(app)}" &&
                    server == state.status.server.residentBytes
            }
        }

    @Test
    fun `on a slow link the UI's memory does not wait for the server`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            val ui = SeparateUiProcess(residentBytes = 300 * MEGABYTE)
            app.runUiAs(ui)
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 300 MB" }
            app.network.latencyMs = 1_500
            ui.residentBytes = 500 * MEGABYTE
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 500 MB" }
            awaitCondition { textOf(LanguageServerTags.STATUS_TOTAL_MEMORY) == "Total ${shownTotal(app)}" }
            app.network.latencyMs = 0
        }
}
