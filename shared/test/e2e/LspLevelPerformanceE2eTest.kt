package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.LspLevel
import org.example.kotlineditor.state.LspLevelSource
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test

private const val FILES = 40

/** Changing the level of the language servers with many files open. */
@OptIn(ExperimentalTestApi::class)
class LspLevelPerformanceE2eTest {
    private val servers = FakePhpServers().chosen()
    private val project =
        FakePhpServers.project().apply {
            for (index in 1..FILES) file("src/f$index.php", "<?php\nfunction f$index() { return $index; }\n")
        }

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `Off, Basic and Full with many files open stay fast`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            for (index in 1..FILES) {
                val path = "${project.path}/src/f$index.php"
                app.project.editor.open(FileEntry(path = path, name = "f$index.php", isDirectory = false))
            }
            awaitServer(app) { it.isRunning() }
            for (level in listOf(LspLevel.Off, LspLevel.Basic, LspLevel.Full)) {
                measure("level ${level.name}", 5_000) {
                    onNodeWithTag(AppTags.LSP_LEVEL_BUTTON).performClick()
                    awaitTag(AppTags.lspLevel(level.name)).performClick()
                    awaitTag(AppTags.lspScope(LspLevelSource.Project.name)).performClick()
                    awaitCondition { app.project.lspLevel == level }
                    renderFrame()
                }
            }
            awaitServer(app) { it.isRunning() }
        }
}
