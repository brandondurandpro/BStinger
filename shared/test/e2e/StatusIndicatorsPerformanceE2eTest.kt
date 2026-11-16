package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test

private const val FRAMES = 200

// The keys of the indicators the status bar shows, the built-in ones and the ones of the language servers.
private val INDICATORS = listOf("LanguageServers", "Memory", "Cpu", "Caret", "LineSeparator", "Encoding", "Language")

/** Cost of hiding and showing the indicators of the status bar. */
@OptIn(ExperimentalTestApi::class)
class StatusIndicatorsPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `drawing a status bar whose indicators change at every frame`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(AppTags.STATUS_CARET)
            val indicators = app.project.statusIndicators
            measure("$FRAMES frames of the status bar", budgetMillis = 10_000, budgetMegabytes = 400) {
                repeat(FRAMES) { frame ->
                    val shown = frame % 2 == 0
                    INDICATORS.forEach { indicators.setShown(it, shown) }
                    renderFrame()
                }
            }
        }
}
