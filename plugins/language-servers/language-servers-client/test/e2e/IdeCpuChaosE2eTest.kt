package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** The processor use shown when the network or the IDE server fails. */
@OptIn(ExperimentalTestApi::class)
class IdeCpuChaosE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `while the network is down the UI's CPU still follows, and the server's is the last known`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            val ui = SeparateUiProcess(cpuPercent = 30)
            app.runUiAs(ui)
            awaitCpuText("CPU UI 30% · Server ")
            app.network.goDown()
            awaitReconnecting()
            val serverBefore = app.state.session.languageServers.cpu.serverPercent
            ui.cpuPercent = 90
            awaitCpuText("CPU UI 90% · Server ")
            assertEquals(serverBefore, app.state.session.languageServers.cpu.serverPercent)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(LanguageServerTags.STATUS_CPU)
        }

    @Test
    fun `a restarted server is measured again`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            app.runUiAs(SeparateUiProcess(cpuPercent = 30))
            awaitCpuText("CPU UI 30% · Server ")
            val state = app.state.session.languageServers
            val before = state.status
            app.process.restart()
            awaitCondition { state.status !== before && state.status.server.heapMaxBytes > 0 }
            // The new server has no reference yet: its CPU is not measured at first, then it is again.
            awaitCpuText("CPU UI 30% · Server ")
        }
}
