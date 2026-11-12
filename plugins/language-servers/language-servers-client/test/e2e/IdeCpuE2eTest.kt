package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains

/** The processor use of the UI, of the IDE server and of both, in the status bar and the Language Servers window. */
@OptIn(ExperimentalTestApi::class)
class IdeCpuE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `a server in the process of the UI shows one number`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitTag(LanguageServerTags.STATUS_TOTAL_MEMORY)
            // The first measure comes a few seconds after the start, and is read from this very process.
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).matches(Regex("CPU \\d+%")) }
            val cpu = app.state.session.languageServers.cpu
            kotlin.test.assertTrue(cpu.sameProcess)
        }

    @Test
    fun `a server in another process is shown beside the UI`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.runUiAs(SeparateUiProcess(cpuPercent = 30))
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).matches(Regex("CPU UI 30% · Server \\d+%")) }
        }

    @Test
    fun `the number follows the CPU of the UI`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val ui = SeparateUiProcess(cpuPercent = 30)
            app.runUiAs(ui)
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).startsWith("CPU UI 30% · ") }
            ui.cpuPercent = 180
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).startsWith("CPU UI 180% · ") }
            ui.cpuPercent = 0
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).startsWith("CPU UI 0% · ") }
        }

    @Test
    fun `a UI whose CPU cannot be read shows the server alone`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.runUiAs(SeparateUiProcess(cpuPercent = null))
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).matches(Regex("CPU Server \\d+%")) }
        }

    @Test
    fun `a click opens the Language Servers window, which shows the same numbers`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.runUiAs(SeparateUiProcess(cpuPercent = 30))
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).startsWith("CPU UI 30%") }
            awaitTag(LanguageServerTags.STATUS_CPU).performClick()
            awaitTag(LanguageServerTags.LANGUAGE_SERVERS_IDE)
            awaitCondition { textOf(LanguageServerTags.CPU_UI) == "30%" }
            awaitCondition { textOf(LanguageServerTags.CPU_SERVER).matches(Regex("\\d+%")) }
            awaitCondition { textOf(LanguageServerTags.CPU_TOTAL).matches(Regex("\\d+%")) }
        }

    @Test
    fun `the tooltip tells what is measured`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.runUiAs(SeparateUiProcess(cpuPercent = 30))
            awaitCondition { textOf(LanguageServerTags.STATUS_CPU).startsWith("CPU UI 30%") }
            awaitTag(LanguageServerTags.STATUS_CPU).performMouseInput { moveTo(center) }
            awaitTag(LanguageServerTags.STATUS_CPU_TOOLTIP)
            val tooltip = textOf(LanguageServerTags.STATUS_CPU_TOOLTIP)
            assertContains(tooltip, "UI process: 30%")
            assertContains(tooltip, "IDE server process: ")
            assertContains(tooltip, "percent of one core")
        }
}
