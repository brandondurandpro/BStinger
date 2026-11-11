package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.memory.ProcessIdentity
import org.example.kotlineditor.memory.ProcessMemory
import org.example.kotlineditor.memory.ProcessMemoryReader
import org.example.kotlineditor.state.UiProcess
import org.example.kotlineditor.state.memoryText
import org.example.kotlineditor.ui.LanguageServerTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A megabyte, in bytes. */
internal const val MEGABYTE = 1024L * 1024L

/**
 * The UI as a process of its own, as in the desktop app, whose server is a child process: the server of an e2e app
 * runs in the process of the UI. Its memory is [residentBytes], which a test changes to see the UI follow.
 */
internal class SeparateUiProcess(@Volatile var residentBytes: Long? = 300 * MEGABYTE) {
    private val other = ProcessIdentity(pid = ProcessHandle.current().pid() + 1, startedAtMillis = 1)

    /** What the IDE reads the memory of its UI from. */
    val process = UiProcess(other) { residentBytes?.let { ProcessMemory(privateBytes = it, residentBytes = it) } }
}

/** Shows the UI of [app] as [ui], a process of its own. */
internal fun E2eApp.runUiAs(ui: SeparateUiProcess) {
    state.session.languageServers.uiProcess = ui.process
}

/**
 * The memory of the UI, of the IDE server and of both, in the status bar and the Language Servers window. Linux and
 * Windows, where the tests run, both tell the memory of a process.
 */
@OptIn(ExperimentalTestApi::class)
class IdeMemoryE2eTest {
    private val project = SampleProject.standard()
    private val reader = ProcessMemoryReader.forCurrentOs()

    @AfterTest
    fun cleanup() {
        project.delete()
        reader.close()
    }

    @Test
    fun `a server in the process of the UI is shown once, with the memory of that process`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitTag(LanguageServerTags.STATUS_TOTAL_MEMORY)
            val memory = app.state.session.languageServers.memory
            assertTrue(memory.sameProcess)
            awaitCondition { textOf(LanguageServerTags.STATUS_TOTAL_MEMORY) == "UI + Server ${shownTotal(app)}" }
            awaitNoTag(LanguageServerTags.STATUS_UI_MEMORY)
            awaitNoTag(LanguageServerTags.STATUS_SERVER_MEMORY)
            // The memory of the process as the OS counts it, not the heap: several times the heap in use.
            val resident = checkNotNull(reader.read(ProcessHandle.current().pid(), null)).residentBytes
            val shown = checkNotNull(app.state.session.languageServers.memory.totalBytes)
            assertTrue(shown in resident / 2..resident * 2, "shown ${memoryText(shown)}, is ${memoryText(resident)}")
        }

    @Test
    fun `a server in another process is shown beside the UI, and the total adds them`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.runUiAs(SeparateUiProcess(residentBytes = 300 * MEGABYTE))
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 300 MB" }
            val state = app.state.session.languageServers
            // The server's is the resident memory it measured of its own process.
            assertEquals(state.status.server.residentBytes, state.memory.serverBytes)
            awaitCondition {
                val server = checkNotNull(state.memory.serverBytes)
                val total = 300 * MEGABYTE + server / MEGABYTE * MEGABYTE
                val serverShown = textOf(LanguageServerTags.STATUS_SERVER_MEMORY) == "Server ${memoryText(server)}"
                val totalShown = textOf(LanguageServerTags.STATUS_TOTAL_MEMORY) == "Total ${memoryText(total)}"
                serverShown && totalShown
            }
        }

    @Test
    fun `the numbers follow the memory of the UI`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            val ui = SeparateUiProcess(residentBytes = 300 * MEGABYTE)
            app.runUiAs(ui)
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 300 MB" }
            val before = textOf(LanguageServerTags.STATUS_TOTAL_MEMORY)
            ui.residentBytes = 2 * 1024 * MEGABYTE
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 2.0 GB" }
            awaitCondition { textOf(LanguageServerTags.STATUS_TOTAL_MEMORY) != before }
        }

    @Test
    fun `a UI whose memory cannot be read shows the server alone, and no total`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.runUiAs(SeparateUiProcess(residentBytes = null))
            awaitTag(LanguageServerTags.STATUS_SERVER_MEMORY)
            awaitNoTag(LanguageServerTags.STATUS_UI_MEMORY)
            awaitNoTag(LanguageServerTags.STATUS_TOTAL_MEMORY)
        }

    @Test
    fun `a click opens the Language Servers window, which shows the same numbers`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            awaitTag(LanguageServerTags.STATUS_TOTAL_MEMORY).performClick()
            awaitTag(LanguageServerTags.LANGUAGE_SERVERS_IDE)
            awaitCondition { textOf(LanguageServerTags.MEMORY_TOTAL) == shownTotal(app) }
            awaitNoTag(LanguageServerTags.MEMORY_UI)

            app.runUiAs(SeparateUiProcess(residentBytes = 300 * MEGABYTE))
            awaitCondition { textOf(LanguageServerTags.MEMORY_UI) == "300 MB" }
            awaitCondition {
                val shownInBar = textOf(LanguageServerTags.STATUS_SERVER_MEMORY).removePrefix("Server ")
                textOf(LanguageServerTags.MEMORY_SERVER) == shownInBar
            }
            awaitCondition { textOf(LanguageServerTags.MEMORY_TOTAL) == shownTotal(app) }
        }

    @Test
    fun `the tooltip tells what is measured`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            app.runUiAs(SeparateUiProcess(residentBytes = 300 * MEGABYTE))
            awaitCondition { textOf(LanguageServerTags.STATUS_UI_MEMORY) == "UI 300 MB" }
            awaitTag(LanguageServerTags.STATUS_UI_MEMORY).performMouseInput { moveTo(center) }
            awaitTag(LanguageServerTags.STATUS_MEMORY_TOOLTIP)
            val tooltip = textOf(LanguageServerTags.STATUS_MEMORY_TOOLTIP)
            assertContains(tooltip, "UI process: 300 MB")
            assertContains(tooltip, "IDE server process: ")
            assertContains(tooltip, "Total: ")
        }
}

/** The total of [app]'s memory, as text. */
internal fun shownTotal(app: E2eApp): String =
    memoryText(checkNotNull(app.state.session.languageServers.memory.totalBytes))
