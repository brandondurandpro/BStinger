package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.minutes

/**
 * Find usages while the network or the IDE server fails, with a language server that indexes the project for a while
 * after it started and finds no usage meanwhile (like intelephense): the "Indexing" notice never stays once the
 * request is over, and the usages are listed once everything is back.
 *
 * In `src/Greeter.php` ([codeInsightProject]), `greet` is declared at line 13 and used twice in `src/index.php`.
 */
@OptIn(ExperimentalTestApi::class)
class FindUsagesChaosE2eTest {
    private val project = codeInsightProject()
    private val servers = FakePhpServers("--progress", "$INDEXING_SECONDS", "--blind-until-indexed").chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.open(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/Greeter.php")
        awaitServer(app) { it.isRunning() || it.state == LanguageServerState.Indexing }
        return app
    }

    private fun ComposeUiTest.findUsagesOfGreet() {
        clickAt(13, 22)
        editorKeys { withKeyDown(Key.AltLeft) { pressKey(Key.F7) } }
    }

    private fun ComposeUiTest.notice(): String =
        if (onAllNodes(hasTestTag(EditorTags.NOTICE)).fetchSemanticsNodes().isEmpty()) "" else textOf(EditorTags.NOTICE)

    private fun ComposeUiTest.listed(): Boolean =
        onAllNodes(hasTestTag(EditorTags.LOCATIONS)).fetchSemanticsNodes().isNotEmpty()

    private fun ComposeUiTest.awaitUsagesOfGreet() {
        awaitTag(EditorTags.LOCATIONS, timeout = SERVER_START_MS)
        assertEquals("Usages of greet  2 usages", textOf(EditorTags.LOCATIONS_TITLE))
        editorKeys { pressKey(Key.Escape) }
        awaitNoTag(EditorTags.LOCATIONS)
    }

    @Test
    fun `the network going down while the usages wait for the index leaves nothing stuck`() =
        runComposeUiTest {
            val app = open()
            findUsagesOfGreet()
            awaitCondition(SERVER_START_MS) { notice() == INDEXING }
            app.network.goDown()
            awaitReconnecting()
            app.network.comeBack()
            awaitReconnected()
            // The request ends, listed or not ready: the notice does not stay.
            awaitCondition(SERVER_START_MS) { listed() || notice() != INDEXING }
            if (listed()) editorKeys { pressKey(Key.Escape) }
            awaitServer(app) { it.isRunning() }
            findUsagesOfGreet()
            awaitUsagesOfGreet()
        }

    @Test
    fun `a restart of the IDE server while the usages wait for the index lists them once it indexed again`() =
        // Two indexings and a restart: more than the default minute on a slow runner.
        runComposeUiTest(testTimeout = RESTART_TEST_TIMEOUT) {
            val app = open()
            findUsagesOfGreet()
            awaitCondition(SERVER_START_MS) { notice() == INDEXING }
            app.process.restart()
            awaitReconnected()
            awaitCondition(SERVER_START_MS) { listed() || notice() != INDEXING }
            if (listed()) editorKeys { pressKey(Key.Escape) }
            awaitServer(app) { it.isRunning() || it.state == LanguageServerState.Indexing }
            findUsagesOfGreet()
            awaitUsagesOfGreet()
            // The counts are those of the new server, once it indexed.
            awaitCondition(SERVER_START_MS) { textOf(EditorTags.usageHint(13)) == "2 usages" }
        }

    @Test
    fun `on a slow link the usage hint lists the usages once the index is there`() =
        runComposeUiTest {
            val app = open()
            app.network.latencyMs = SLOW_LINK_MS
            awaitTag(EditorTags.usageHint(13), timeout = SERVER_START_MS, unmerged = true)
            awaitCondition(SERVER_START_MS) { textOf(EditorTags.usageHint(13)) == "2 usages" }
            onNodeWithTag(EditorTags.usageHint(13), useUnmergedTree = true).performClick()
            awaitUsagesOfGreet()
        }

    private companion object {
        val RESTART_TEST_TIMEOUT = 3.minutes
        const val INDEXING_SECONDS = 4
        const val SLOW_LINK_MS = 400L
        const val INDEXING = "Indexing the project… the results will show once it is done"
    }
}
