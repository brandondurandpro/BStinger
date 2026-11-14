package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.StatusIndicator
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.LanguageServerTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The first CPU number comes a few seconds after the start.
private const val CPU_TIMEOUT = 30_000L

/** The menu at the right of the status bar: which indicators the user keeps shown. */
@OptIn(ExperimentalTestApi::class)
class StatusIndicatorsE2eTest {
    private val project = SampleProject.standard()
    private val dir = Files.createTempDirectory("status-indicators")

    @AfterTest
    fun cleanup() {
        project.delete()
        dir.toFile().deleteRecursively()
    }

    private fun ComposeUiTest.toggle(indicator: StatusIndicator) {
        // The menu stays open after a choice: it is opened only when it is not.
        val item = AppTags.statusIndicator(indicator.name)
        val open = onAllNodesWithTag(item).fetchSemanticsNodes().isNotEmpty()
        if (!open) awaitTag(AppTags.STATUS_INDICATORS).performClick()
        awaitTag(item).performClick()
    }

    @Test
    fun `every indicator is shown until the user hides one`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(AppTags.STATUS_CARET)
            awaitTag(AppTags.STATUS_LANGUAGE)
            awaitTag(AppTags.STATUS_LINE_SEPARATOR)
            awaitTag(AppTags.STATUS_ENCODING)
            awaitTag(LanguageServerTags.STATUS_CPU, timeout = CPU_TIMEOUT)
            assertTrue(StatusIndicator.entries.all { app.project.statusIndicators.isShown(it) })
        }

    @Test
    fun `a hidden indicator disappears and comes back`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(AppTags.STATUS_CARET)
            toggle(StatusIndicator.Caret)
            awaitNoTag(AppTags.STATUS_CARET)
            // The others stay, and the menu stays open for the next choice.
            awaitTag(AppTags.STATUS_LANGUAGE)
            toggle(StatusIndicator.Language)
            awaitNoTag(AppTags.STATUS_LANGUAGE)
            assertFalse(app.project.statusIndicators.isShown(StatusIndicator.Language))
            awaitTag(AppTags.statusIndicator(StatusIndicator.Caret.name)).performClick()
            awaitTag(AppTags.STATUS_CARET)
        }

    @Test
    fun `the memory and the CPU of the IDE are hidden on their own`() =
        runComposeUiTest {
            launchWithProject(project)
            awaitTag(LanguageServerTags.STATUS_TOTAL_MEMORY)
            toggle(StatusIndicator.Memory)
            awaitNoTag(LanguageServerTags.STATUS_TOTAL_MEMORY)
            awaitTag(LanguageServerTags.STATUS_CPU, timeout = CPU_TIMEOUT)
            toggle(StatusIndicator.Cpu)
            awaitNoTag(LanguageServerTags.STATUS_CPU)
        }

    @Test
    fun `what is wrong is always shown`() =
        runComposeUiTest {
            val app = launchWithProject(project, chaos = true)
            StatusIndicator.entries.forEach { app.project.statusIndicators.setShown(it, false) }
            app.network.goDown()
            awaitReconnecting()
            app.network.comeBack()
            awaitReconnected()
        }

    @Test
    fun `the choice is kept for the next session`() {
        val file = dir.resolve("kept.properties").toFile()
        runComposeUiTest {
            launchWithProject(project, FilePreferenceStore(file))
            toggle(StatusIndicator.Encoding)
            toggle(StatusIndicator.Cpu)
            awaitCondition {
                FilePreferenceStore(file).load()[PreferenceKeys.STATUS_HIDDEN] == "Cpu,Encoding"
            }
        }
        runComposeUiTest {
            val app = launchWithProject(project, FilePreferenceStore(file))
            assertFalse(app.project.statusIndicators.isShown(StatusIndicator.Encoding))
            assertFalse(app.project.statusIndicators.isShown(StatusIndicator.Cpu))
            assertTrue(app.project.statusIndicators.isShown(StatusIndicator.Memory))
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(AppTags.STATUS_CARET)
            awaitNoTag(AppTags.STATUS_ENCODING)
            assertEquals(5, StatusIndicator.entries.count { app.project.statusIndicators.isShown(it) })
        }
    }
}
