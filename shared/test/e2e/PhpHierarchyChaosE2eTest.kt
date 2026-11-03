package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The call hierarchy when the network or the server fails. */
@OptIn(ExperimentalTestApi::class)
class PhpHierarchyChaosE2eTest {
    private val project = codeInsightProject().apply { file("src/calls.php", CALLS_PHP) }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    // The callers of leaf shown: the root, then middle.
    private fun ComposeUiTest.openCallers(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/calls.php")
        awaitServer(app) { it.isRunning() }
        awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
        clickAt(LEAF_LINE, FUNCTION_NAME_COLUMN)
        callHierarchy()
        awaitTag(EditorTags.hierarchyRow(1), timeout = SERVER_START_MS)
        editorKeys { pressKey(Key.DirectionDown) }
        return app
    }

    @Test
    fun `a row expanded while the network is down says so, and expands once it is back`() =
        runComposeUiTest {
            val app = openCallers()
            app.network.goDown()
            awaitReconnecting()
            editorKeys { pressKey(Key.DirectionRight) }
            awaitCondition(SERVER_START_MS) { "not ready" in textOf(EditorTags.hierarchyRow(1)) }
            awaitNoTag(EditorTags.HIERARCHY_LOADING)
            app.network.comeBack()
            awaitReconnected()
            // Collapsed, then expanded again: asked again.
            editorKeys { pressKey(Key.DirectionLeft) }
            editorKeys { pressKey(Key.DirectionRight) }
            awaitTag(EditorTags.hierarchyRow(2), timeout = SERVER_START_MS)
            assertTrue("top" in textOf(EditorTags.hierarchyRow(2)))
            assertTrue("not ready" !in textOf(EditorTags.hierarchyRow(1)))
        }

    @Test
    fun `after the server restarts the rows shown still expand`() =
        runComposeUiTest {
            val app = openCallers()
            app.process.restart()
            awaitReconnected()
            awaitServer(app) { it.isRunning() }
            var expanded = false
            var attempt = 0
            // The restarted server needs the document again before it answers.
            while (!expanded && attempt < ATTEMPTS) {
                attempt++
                // Right asks again for the children of a row the server could not give.
                editorKeys { pressKey(Key.DirectionRight) }
                expanded = runCatching { awaitTag(EditorTags.hierarchyRow(2), timeout = ATTEMPT_MS) }.isSuccess
            }
            assertTrue(expanded, "the restarted server never gave the callers of middle")
            assertTrue("top" in textOf(EditorTags.hierarchyRow(2)))
        }

    @Test
    fun `on a slow link the tree shows it is loading, and a late answer does not reopen a closed tree`() =
        runComposeUiTest {
            val app = openCallers()
            app.network.latencyMs = SLOW_MS
            editorKeys { pressKey(Key.DirectionRight) }
            awaitTag(EditorTags.HIERARCHY_LOADING)
            awaitTag(EditorTags.hierarchyRow(2), timeout = SERVER_START_MS)
            awaitNoTag(EditorTags.HIERARCHY_LOADING)
            // Switched to callees while the callers of top load: their late answer goes nowhere.
            editorKeys { pressKey(Key.DirectionDown) }
            editorKeys { pressKey(Key.DirectionRight) }
            awaitTag(EditorTags.HIERARCHY_SWITCH).performClick()
            awaitCondition(SERVER_START_MS) { textOf(EditorTags.HIERARCHY_TITLE) == "Callees of leaf" }
            awaitTag(EditorTags.HIERARCHY_EMPTY, timeout = SERVER_START_MS)
            assertEquals("No callees", textOf(EditorTags.HIERARCHY_EMPTY))
            editorKeys { pressKey(Key.Escape) }
            awaitNoTag(EditorTags.HIERARCHY)
            repeat(SETTLE_CHECKS) {
                awaitNoTag(EditorTags.HIERARCHY)
                waitForIdle()
            }
        }
}

private const val ATTEMPTS = 12
private const val ATTEMPT_MS = 2_000L
private const val SLOW_MS = 400L
private const val SETTLE_CHECKS = 5
