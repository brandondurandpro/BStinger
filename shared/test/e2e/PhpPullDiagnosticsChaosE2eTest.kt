package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test

private val UNKNOWN_CALL_PHP = listOf("<?php", "helper();", "").joinToString("\n")
private const val UNKNOWN_CALL_LINE = 1

/** Diagnostics pulled from the server when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class PhpPullDiagnosticsChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/calls.php", UNKNOWN_CALL_PHP) }
    private val servers = FakePhpServers("--pull-diagnostics").chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun error(line: Int) = EditorTags.diagnostic(line, "Error")

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/calls.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(error(UNKNOWN_CALL_LINE), timeout = SERVER_START_MS, unmerged = true)
        return app
    }

    @Test
    fun `a call typed while the link is silent gets its problem once it carries data again`() =
        runComposeUiTest {
            val app = open()
            app.network.blackHole = true
            caretToEnd()
            typeCode("other")
            typeCode("();")
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            awaitTag(error(UNKNOWN_CALL_LINE + 1), timeout = SERVER_START_MS, unmerged = true)
        }

    @Test
    fun `after the server restarts, the problems are asked for again without an edit`() =
        runComposeUiTest {
            val app = open()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            awaitTag(error(UNKNOWN_CALL_LINE), timeout = SERVER_START_MS, unmerged = true)
        }

    @Test
    fun `on a slow link, the problems of the last text win`() =
        runComposeUiTest {
            val app = open()
            // Below the second after which a silent link is taken for dead.
            app.network.latencyMs = 400
            caretToEnd()
            typeCode("other")
            typeCode("();")
            awaitTag(error(UNKNOWN_CALL_LINE + 1), timeout = SERVER_START_MS, unmerged = true)
            awaitTag(error(UNKNOWN_CALL_LINE), unmerged = true)
        }
}
