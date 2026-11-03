package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val CALLS = 2_000

/** Diagnostics pulled for a PHP file of 2,000 calls of unknown functions: arriving, and pulled again after an edit. */
@OptIn(ExperimentalTestApi::class)
class PhpPullDiagnosticsPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val calls = (1..CALLS).map { "unknown$it();" }
            file("src/calls.php", (listOf("<?php") + calls + "").joinToString("\n"))
        }
    private val servers = FakePhpServers("--pull-diagnostics").chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `2,000 pulled problems arrive, and are pulled again after a line typed above them`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/calls.php")
            awaitServer(app) { it.isRunning() }
            val document =
                app.project.editor.activeTab
                    ?.document
            val insight = document?.let { app.project.insight.insightOf(it) }
            measure("$CALLS pulled problems", budgetMillis = 15_000) {
                awaitCondition(SERVER_START_MS) { insight?.diagnostics?.size == CALLS }
                awaitTag(EditorTags.diagnostic(1, "Error"), unmerged = true)
                renderFrame()
            }
            clickAt(0, 0)
            measure("$CALLS problems pulled again after a line typed above", budgetMillis = 10_000) {
                editorKeys { pressKey(Key.MoveEnd) }
                editorKeys { pressKey(Key.Enter) }
                typeCode("x();")
                awaitCondition(SERVER_START_MS) {
                    val first = insight?.diagnostics?.firstOrNull()
                    first?.range?.startLine == 1 && insight.diagnostics.size == CALLS + 1
                }
                renderFrame()
            }
            assertEquals(CALLS + 1, insight?.diagnostics?.size)
        }
}
