package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.plugin.core.API_VERSION
import org.example.kotlineditor.state.SettingsCategory
import org.example.kotlineditor.testkit.PluginFixture
import org.example.kotlineditor.ui.AppTags
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val BUTTON = "plugin.todo.button"

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
        org.example.kotlineditor.ui.Lucide.INSTANCE.getFolder(),
        (host, drag, modifier, composer, changed) -> kotlin.Unit.INSTANCE));
    """.trimIndent()

@OptIn(ExperimentalTestApi::class)
class PluginsSettingsE2eTest {
    private val project = SampleProject.standard()
    private val plugins: File = Files.createTempDirectory("ide-plugins").toFile()
    private val downloads: File = Files.createTempDirectory("ide-downloads").toFile()

    @AfterTest
    fun cleanup() {
        project.delete()
        plugins.deleteRecursively()
        downloads.deleteRecursively()
    }

    private fun ComposeUiTest.openPluginsSettings() {
        onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
        awaitTag(AppTags.PROJECT_SETTINGS).performClick()
        awaitTag(AppTags.SETTINGS)
        onNodeWithTag(AppTags.settingsCategory(SettingsCategory.Plugins.name)).performClick()
    }

    private fun ComposeUiTest.textOf(tag: String): String {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        return texts.joinToString(" ") { it.text }
    }

    private fun archiveOf(id: String, apiVersion: Int = API_VERSION): File {
        val built = File(Files.createTempDirectory(downloads.toPath(), "build").toFile(), id)
        val folder = PluginFixture.create(built.parentFile, id, apiVersion, TODO_PLUGIN)
        return PluginFixture.archive(folder, File(downloads, "$id.plugin"))
    }

    @Test
    fun `a plugin is turned off and on from the settings without a restart`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "todo", API_VERSION, TODO_PLUGIN)
            launchWithProject(project, pluginsDirectory = plugins)
            awaitTag(BUTTON)
            openPluginsSettings()

            awaitTag(AppTags.pluginToggle("todo")).assertTextContains("Disable", substring = true)
            onNodeWithTag(AppTags.pluginToggle("todo")).performClick()
            awaitNoTag(BUTTON)
            awaitCondition { textOf(AppTags.plugin("todo")).contains("Disabled") }

            onNodeWithTag(AppTags.pluginToggle("todo")).performClick()
            awaitTag(BUTTON)
        }

    @Test
    fun `a plugin archive is installed from its path, and installed again to update it`() =
        runComposeUiTest {
            val archive = archiveOf("todo")
            val app = launchWithProject(project, pluginsDirectory = plugins)
            openPluginsSettings()

            onNodeWithTag(AppTags.PLUGIN_PATH).performTextInput(archive.path)
            onNodeWithTag(AppTags.PLUGIN_INSTALL).performClick()
            awaitTag(AppTags.plugin("todo"))
            awaitTag(BUTTON)
            assertEquals(listOf("todo"), checkNotNull(app.installedPlugins).loaded)

            onNodeWithTag(AppTags.PLUGIN_PATH).performTextInput(archive.path)
            onNodeWithTag(AppTags.PLUGIN_INSTALL).performClick()
            awaitCondition { checkNotNull(app.installedPlugins).loaded == listOf("todo") }
            awaitTag(BUTTON)
        }

    @Test
    fun `something that is not a plugin archive is refused with a message`() =
        runComposeUiTest {
            val notAPlugin = File(downloads, "nothing.plugin").apply { writeText("not a zip") }
            launchWithProject(project, pluginsDirectory = plugins)
            openPluginsSettings()

            onNodeWithTag(AppTags.PLUGIN_PATH).performTextInput(notAPlugin.path)
            onNodeWithTag(AppTags.PLUGIN_INSTALL).performClick()

            awaitTag(AppTags.PLUGIN_ERROR).assertTextContains("not a plugin", substring = true)
            assertTrue(plugins.list().orEmpty().isEmpty())
        }

    @Test
    fun `a plugin built for another API is listed with the reason it is off`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "future", API_VERSION + 1, TODO_PLUGIN)
            launchWithProject(project, pluginsDirectory = plugins)
            openPluginsSettings()

            awaitTag(AppTags.plugin("future"))
            awaitCondition { textOf(AppTags.plugin("future")).contains("plugin API") }
            awaitNoTag(BUTTON)
        }
}
