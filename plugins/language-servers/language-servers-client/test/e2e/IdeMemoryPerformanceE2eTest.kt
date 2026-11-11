package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.state.UiProcess
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val READS = 5_000
private const val FRAMES = 200

/** Cost of showing the memory of the IDE: reading it, drawing it, and what it sends to the server (nothing). */
@OptIn(ExperimentalTestApi::class)
class IdeMemoryPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `reading the memory of the UI costs a few microseconds`() =
        runComposeUiTest {
            launchWithProject(project)
            val ui = UiProcess.current()
            assertNotNull(ui.memory())
            // One small read of the OS each: no `ps`, no child process. Measured at 30 µs a read.
            measure("$READS reads of the UI's memory", budgetMillis = 2_000, budgetMegabytes = 400) {
                repeat(READS) { ui.memory() }
            }
        }

    @Test
    fun `drawing a memory that changes at every frame`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitTag(LanguageServerTags.STATUS_TOTAL_MEMORY)
            val separate = SeparateUiProcess()
            val own = app.state.session.languageServers.uiProcess
            // The worst case: at every frame the items change from one number to three and back.
            measure("$FRAMES frames of the memory items", budgetMillis = 10_000, budgetMegabytes = 400) {
                repeat(FRAMES) { frame ->
                    app.state.session.languageServers.uiProcess = if (frame % 2 == 0) separate.process else own
                    renderFrame()
                }
            }
        }

    @Test
    fun `the memory of the UI asks nothing of the server`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val ui = SeparateUiProcess()
            app.runUiAs(ui)
            awaitTag(LanguageServerTags.STATUS_UI_MEMORY)
            app.client.network.recording = true
            // Several samples of the UI's memory, each one different.
            repeat(3) { round ->
                ui.residentBytes = (400L + round * 100) * MEGABYTE
                awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI ${400 + round * 100} MB" }
            }
            app.client.network.recording = false
            val frames = app.client.network.snapshot()
            val sent = frames.filter { it.direction == Direction.Sent && it.kind == "Request" }
            val asked = sent.mapNotNull { it.method }
            println("PERF memory samples: ${sent.size} requests sent while the UI's memory changed 3 times")
            assertTrue(asked.none { it.contains("emory") }, "requests sent: $asked")
            assertTrue(sent.size <= 6, "${sent.size} requests in a few seconds: $asked")
        }
}
