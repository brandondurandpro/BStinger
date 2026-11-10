package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.plugin.host.API_VERSION
import org.example.kotlineditor.plugin.host.PluginProblem
import org.example.kotlineditor.plugin.host.UnloadOutcome
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.testkit.PluginFixture
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val BUTTON = "plugin.todo.button"

// A client plugin written in Java, the way a third party's jar is loaded: a tool window with an empty panel.
private val TODO_PLUGIN =
    """
    c.toolWindow(new org.example.kotlineditor.plugin.ToolWindowContribution(
        new org.example.kotlineditor.state.ToolWindowDefaults(
            new org.example.kotlineditor.state.ToolWindow("plugin.todo"),
            org.example.kotlineditor.state.Dock.Bottom,
            false),
        "$BUTTON",
        org.example.kotlineditor.plugin.StripGroup.Bottom,
        false,
        host -> kotlin.Unit.INSTANCE,
        (composer, changed) -> kotlin.Unit.INSTANCE,
        (host, drag, modifier, composer, changed) -> kotlin.Unit.INSTANCE));
    """.trimIndent()

@OptIn(ExperimentalTestApi::class)
class ExternalPluginE2eTest {
    private val project = SampleProject.standard()
    private val plugins: File = Files.createTempDirectory("ide-plugins").toFile()

    @AfterTest
    fun cleanup() {
        project.delete()
        plugins.deleteRecursively()
    }

    @Test
    fun `a plugin installed as a jar gets its button, and leaves without a restart`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "todo", API_VERSION, body = TODO_PLUGIN)
            val app = launchWithProject(project, pluginsDirectory = plugins)
            val installed = checkNotNull(app.installedPlugins)
            assertEquals(listOf("todo"), installed.loaded)

            awaitTag(BUTTON).performClick()
            awaitCondition { app.project.toolWindows.isVisible(ToolWindow("plugin.todo")) }

            assertEquals(UnloadOutcome.Clean, installed.unload("todo"))
            awaitNoTag(BUTTON)
        }

    @Test
    fun `a plugin built for another API is left off and the IDE starts`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "future", API_VERSION + 1, body = TODO_PLUGIN)
            val app = launchWithProject(project, pluginsDirectory = plugins)
            val installed = checkNotNull(app.installedPlugins)

            assertEquals(emptyList(), installed.loaded)
            assertIs<PluginProblem.ApiMismatch>(installed.offBecause["future"])
            awaitNoTag(BUTTON)
        }

    @Test
    fun `a plugin that crashed its last start stays off until it is enabled`() =
        runComposeUiTest {
            val folder = PluginFixture.create(plugins, "todo", API_VERSION, body = TODO_PLUGIN)
            File(folder, ".loading").writeText("1.0")
            val app = launchWithProject(project, pluginsDirectory = plugins)

            assertIs<PluginProblem.Crashed>(checkNotNull(app.installedPlugins).offBecause["todo"])
            awaitNoTag(BUTTON)
            assertTrue(File(folder, ".loading").exists())
        }
}
