package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Cost of the language servers for the IDE: status traffic and rendering with several servers. */
@OptIn(ExperimentalTestApi::class)
class LanguageServerPerformanceE2eTest {
    private val servers = FakePhpServers().chosen()
    private val roots = 6
    private val project =
        SampleProject().apply {
            file("README.md", "# Monorepo\n")
            // One composer.json per package: one language server per package.
            repeat(roots) { file("src/package$it/composer.json", "{}\n") }
            repeat(roots) { file("src/package$it/index.php", "<?php\necho $it;\n") }
        }

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `an idle language server costs a few status answers per minute`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            // Recording from the start: an answer is named after its request only if the request was recorded.
            app.client.network.recording = true
            openFromTree("src/package0/index.php")
            awaitServer(app) { it.state == LanguageServerState.Running && it.memory != null }
            // Its memory settles once the JVM warmed up.
            idleFor(3_000)
            val windowMs = 10_000L
            val start = System.currentTimeMillis()
            idleFor(windowMs)
            val end = System.currentTimeMillis()
            app.client.network.recording = false
            val answers =
                app.client.network.snapshot().filter {
                    it.direction == Direction.Received && it.method == "LspStatus" && it.timeMs in start..end
                }
            assertTrue(answers.isNotEmpty(), "the long poll answers at least once per hold")
            val bytes = answers.sumOf { it.size }
            println("PERF idle status: ${answers.size} answers, $bytes bytes in ${windowMs / 1_000} s")
            // One answer per 5 s hold, and a few memory changes over 2 % and 5 MB at most: not one per sample.
            assertTrue(answers.size <= 8, "${answers.size} status answers in ${windowMs / 1_000} s")
            assertTrue(bytes < 16 * 1024, "$bytes bytes of status in ${windowMs / 1_000} s")
        }

    @Test
    fun `the Language Servers window renders six servers quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            repeat(roots) { openFromTree("src/package$it/index.php") }
            awaitCondition(SERVER_START_MS * 2) {
                app.languageServers.size == roots && app.languageServers.all { it.state == LanguageServerState.Running }
            }
            val first = app.languageServers.first()
            val label = "open the Language Servers window with $roots servers"
            measure(label, budgetMillis = 1_500, budgetMegabytes = 200) {
                onNodeWithTag(LanguageServerTags.statusLanguageServer(first.id)).performClick()
                renderFrame()
            }
            awaitTag(LanguageServerTags.languageServerRow(app.languageServers.last().id))
        }
}

// Lets the app run for [millis]: the status long poll runs on the UI thread, which a sleep would block.
@OptIn(ExperimentalTestApi::class)
private fun ComposeUiTest.idleFor(millis: Long) {
    val deadline = System.currentTimeMillis() + millis
    awaitCondition(millis + IDLE_MARGIN_MS) { System.currentTimeMillis() >= deadline }
}

private const val IDLE_MARGIN_MS = 5_000L
