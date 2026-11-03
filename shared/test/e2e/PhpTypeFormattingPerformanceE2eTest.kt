package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import kotlin.test.AfterTest
import kotlin.test.Test

private const val STATEMENTS = 5_000

/** Formatting while typing at the end of a PHP file of 5,000 statements. */
@OptIn(ExperimentalTestApi::class)
class PhpTypeFormattingPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val statements = (1..STATEMENTS).map { "\$v$it = $it;" }
            file("src/long.php", (listOf("<?php") + statements + "\$last = 0").joinToString("\n"))
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `a semicolon typed after 5,000 lines is formatted quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/long.php")
            awaitServer(app) { it.isRunning() }
            val document =
                app.project.editor.activeTab
                    ?.document
            val insight = document?.let { app.project.insight.insightOf(it) }
            awaitCondition(SERVER_START_MS) { insight?.typeFormattingTriggers?.isNotEmpty() == true }
            clickAt(0, 0)
            editorKeys { withKeyDown(Key.CtrlLeft) { pressKey(Key.MoveEnd) } }
            val last = STATEMENTS + 1
            measure("a semicolon formatted after $STATEMENTS lines", budgetMillis = 3_000) {
                typeCode(" ;")
                awaitCondition { document?.lines?.getOrNull(last) == "\$last = 0;" }
                renderFrame()
            }
        }
}
