package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// A function without a docblock on line 2: its lens adds one.
private val SHOUT_PHP =
    listOf(
        "<?php",
        "",
        "function shout(\$text)",
        "{",
        "    return \$text;",
        "}",
        "",
    ).joinToString("\n")
private const val SHOUT_LINE = 2
private const val DOCBLOCK_START = "/**"

/** Code lenses when the network or the server fails: shown, followed, run once. */
@OptIn(ExperimentalTestApi::class)
class PhpCodeLensChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/helpers.php", SHOUT_PHP) }
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

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/helpers.php")
        awaitServer(app) { it.isRunning() }
        awaitLens(SHOUT_LINE)
        return app
    }

    private fun ComposeUiTest.awaitLens(line: Int) =
        awaitTag(EditorTags.codeLens(line, 0), timeout = SERVER_START_MS, unmerged = true)

    private fun ComposeUiTest.clickLens(line: Int) =
        onNodeWithTag(EditorTags.codeLens(line, 0), useUnmergedTree = true).performClick()

    @Test
    fun `a lens whose answer was lost runs its command once`() =
        runComposeUiTest {
            val app = open()
            app.network.dropFromServer = true
            clickLens(SHOUT_LINE)
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(SHOUT_LINE) == DOCBLOCK_START }
            assertEquals(1, app.lines().count { it == DOCBLOCK_START })
        }

    @Test
    fun `a lens clicked while the network is down runs once it is back, once`() =
        runComposeUiTest {
            val app = open()
            app.network.goDown()
            awaitReconnecting()
            clickLens(SHOUT_LINE)
            mainClock.advanceTimeBy(1_000)
            assertEquals(SHOUT_PHP.lines().getOrNull(SHOUT_LINE), app.lines().getOrNull(SHOUT_LINE))
            app.network.comeBack()
            awaitReconnected()
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(SHOUT_LINE) == DOCBLOCK_START }
            assertEquals(1, app.lines().count { it == DOCBLOCK_START })
        }

    @Test
    fun `lines typed while the link is silent move the lens, the server confirms it after`() =
        runComposeUiTest {
            val app = open()
            app.network.blackHole = true
            clickAt(0, 0)
            editorKeys { pressKey(Key.MoveEnd) }
            editorKeys { pressKey(Key.Enter) }
            awaitLens(SHOUT_LINE + 1)
            awaitNoTag(EditorTags.codeLens(SHOUT_LINE, 0), unmerged = true)
            awaitReconnecting()
            app.network.blackHole = false
            awaitReconnected()
            awaitLens(SHOUT_LINE + 1)
            assertEquals("Add docblock", textOf(EditorTags.codeLens(SHOUT_LINE + 1, 0)))
            assertEquals("function shout(\$text)", app.lines().getOrNull(SHOUT_LINE + 1))
        }

    @Test
    fun `after the server restarts, the lenses are asked again and still run`() =
        runComposeUiTest {
            val app = open()
            val before = app.lensToken()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            // The new server process gave the lenses again, under a new list.
            awaitCondition(SERVER_START_MS) { app.lensToken().let { it.isNotEmpty() && it != before } }
            awaitLens(SHOUT_LINE)
            clickLens(SHOUT_LINE)
            awaitCondition(SERVER_START_MS) { app.lines().getOrNull(SHOUT_LINE) == DOCBLOCK_START }
            assertEquals(1, app.lines().count { it == DOCBLOCK_START })
        }

    private fun E2eApp.lensToken(): String {
        val document = project.editor.activeTab?.document
        val insight = document?.let { project.insight.insightOf(it) }
        return insight?.codeLensToken.orEmpty()
    }
}
