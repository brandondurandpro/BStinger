package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Formatting while typing when the network or the server fails: late answers never override newer typing. */
@OptIn(ExperimentalTestApi::class)
class PhpTypeFormattingChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/format.php", FORMAT_PHP) }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun E2eApp.lines(): List<String> =
        project.editor.activeTab
            ?.document
            ?.lines
            ?.toList()
            .orEmpty()

    private fun E2eApp.triggersKnown(): Boolean {
        val document = project.editor.activeTab?.document
        return document?.let { project.insight.insightOf(it) }?.typeFormattingTriggers?.isNotEmpty() == true
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/format.php")
        awaitServer(app) { it.isRunning() }
        awaitCondition(SERVER_START_MS) { app.triggersKnown() }
        clickAt(STATEMENT_LINE, 4)
        editorKeys { pressKey(Key.MoveEnd) }
        return app
    }

    @Test
    fun `on a slow link, an answer arriving after more typing is dropped`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = 400
            typeCode(" ;")
            typeCode(" x")
            mainClock.advanceTimeBy(1_500)
            waitForIdle()
            app.network.latencyMs = 0
            awaitCondition { app.lines().getOrNull(STATEMENT_LINE) == "    \$a = 1 ; x" }
        }

    @Test
    fun `a semicolon typed while the network is down is formatted once it is back, once`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            typeCode(" ;")
            assertEquals("    \$a = 1 ;", app.lines().getOrNull(STATEMENT_LINE))
            app.network.comeBack()
            awaitReconnected()
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(STATEMENT_LINE) == FORMATTED_STATEMENT }
        }

    @Test
    fun `after the server restarts, its triggers are asked again and typing formats`() =
        runComposeUiTest {
            val app = open()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            awaitCondition(SERVER_START_MS) { app.triggersKnown() }
            typeCode(" ;")
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(STATEMENT_LINE) == FORMATTED_STATEMENT }
        }
}
