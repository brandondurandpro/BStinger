package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.plugin.core.API_VERSION
import org.example.kotlineditor.testkit.PluginFixture
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.EditorTags
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
class ExtensionsPanelE2eTest {
    private val project = SampleProject.standard()
    private val plugins: File = Files.createTempDirectory("ide-plugins").toFile()

    @AfterTest
    fun cleanup() {
        project.delete()
        plugins.deleteRecursively()
    }

    private fun ComposeUiTest.openExtensions() {
        onNodeWithTag(AppTags.TOOL_EXTENSIONS).performClick()
        awaitTag(AppTags.EXTENSIONS)
    }

    private fun ComposeUiTest.textOf(tag: String): String {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        return texts.joinToString(" ") { it.text }
    }

    private fun ComposeUiTest.openCard(key: String) {
        awaitTag(AppTags.extension(key)).performClick()
        awaitTag(AppTags.EXTENSION_TAB)
        awaitTag(EditorTags.extensionTab(key))
    }

    @Test
    fun `the button of the left strip opens the extensions, which are turned off and on`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "todo", API_VERSION, TODO_PLUGIN)
            launchWithProject(project, pluginsDirectory = plugins)
            awaitTag(BUTTON)
            openExtensions()

            // The card says it is on; the switch is in its tab.
            awaitTag(AppTags.extension("plugin:todo")).assertTextContains("On", substring = true)
            onNodeWithTag(AppTags.pluginToggle("todo")).assertDoesNotExist()
            openCard("plugin:todo")
            awaitTag(AppTags.pluginToggle("todo")).assertTextContains("Disable", substring = true)
            onNodeWithTag(AppTags.pluginToggle("todo")).performClick()
            awaitNoTag(BUTTON)
            awaitCondition { textOf(AppTags.pluginToggle("todo")) == "Enable" }

            onNodeWithTag(AppTags.pluginToggle("todo")).performClick()
            awaitTag(BUTTON)

            // The tab stays while the card, still in the window, says it is on.
            awaitTag(AppTags.extension("plugin:todo")).assertTextContains("On", substring = true)
            onNodeWithTag(EditorTags.extensionTabClose("plugin:todo")).performClick()
            awaitNoTag(EditorTags.extensionTab("plugin:todo"))
        }

    @Test
    fun `the search narrows the plugins and the language servers, and says when nothing matches`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "todo", API_VERSION, TODO_PLUGIN)
            PluginFixture.create(plugins, "notes", API_VERSION, "")
            val servers = FakePhpServers().chosen()
            val php = FakePhpServers.project()
            try {
                launchWithProject(php, pluginsDirectory = plugins, languageServers = servers.settings)
                openExtensions()
                awaitTag(AppTags.extension("plugin:todo"))
                awaitTag(AppTags.extension("plugin:notes"))
                awaitTag(AppTags.extension("servers:php"))

                onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextInput("NOT")
                awaitNoTag(AppTags.extension("plugin:todo"))
                awaitNoTag(AppTags.extension("servers:php"))
                awaitTag(AppTags.extension("plugin:notes"))

                onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextClearance()
                onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextInput("fakephp")
                awaitNoTag(AppTags.extension("plugin:notes"))
                awaitTag(AppTags.extension("servers:php"))

                onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextClearance()
                onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextInput("zzz")
                awaitTag(AppTags.EXTENSIONS_EMPTY).assertTextContains("zzz", substring = true)
                awaitNoTag(AppTags.extension("plugin:todo"))

                onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextClearance()
                awaitTag(AppTags.extension("plugin:todo"))
                awaitNoTag(AppTags.EXTENSIONS_EMPTY)
            } finally {
                servers.delete()
                php.delete()
            }
        }

    @Test
    fun `the window has no field to install from a path, which stays in the settings`() =
        runComposeUiTest {
            launchWithProject(project, pluginsDirectory = plugins)
            openExtensions()

            awaitTag(AppTags.EXTENSIONS_SEARCH)
            onNodeWithTag(AppTags.PLUGIN_PATH).assertDoesNotExist()
            onNodeWithTag(AppTags.PLUGIN_INSTALL).assertDoesNotExist()
        }

    @Test
    fun `the language servers of the IDE are listed with the plugins, and one is switched`() =
        runComposeUiTest {
            val servers = FakePhpServers(alternative = true).chosen()
            val php = FakePhpServers.project()
            try {
                launchWithProject(php, languageServers = servers.settings)
                openExtensions()

                openCard("servers:php")
                awaitTag(AppTags.EXTENSIONS_SERVERS)
                awaitTag(AppTags.settingsServer(FakePhpServers.FAKE))
                awaitNoTag(AppTags.settingsServerUse(FakePhpServers.FAKE))
                onNodeWithTag(AppTags.settingsServerUse(FakePhpServers.NEEDS_NODE)).assertIsNotEnabled()

                onNodeWithTag(AppTags.settingsServerUse(FakePhpServers.OTHER)).performClick()
                awaitNoTag(AppTags.settingsServerUse(FakePhpServers.OTHER))
                awaitTag(AppTags.settingsServerUse(FakePhpServers.FAKE))
                awaitCondition { "php=${FakePhpServers.OTHER}" in servers.saved() }
            } finally {
                servers.delete()
                php.delete()
            }
        }

    @Test
    fun `a language server is turned off on its own and on again with the server chosen before`() =
        runComposeUiTest {
            val servers = FakePhpServers(alternative = true).chosen(FakePhpServers.OTHER)
            val php = FakePhpServers.project()
            try {
                val app = launchWithProject(php, languageServers = servers.settings)
                openFromTree("src/index.php")
                awaitServer(app) { it.isRunning() && it.serverName == FakePhpServers.OTHER }
                openExtensions()

                awaitTag(AppTags.extension("servers:php")).assertTextContains("On", substring = true)
                openCard("servers:php")
                awaitTag(AppTags.settingsServerToggle("php")).assertTextContains("Disable", substring = true)
                onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
                awaitCondition { textOf(AppTags.settingsServerToggle("php")) == "Enable" }
                awaitCondition { app.languageServers.none { it.language == "php" } }
                assertTrue("disabled" in servers.saved())
                // The server chosen stays chosen, only no longer in use.
                assertTrue("php=${FakePhpServers.OTHER}" in servers.saved())

                onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
                awaitCondition { textOf(AppTags.settingsServerToggle("php")) == "Disable" }
                awaitServer(app) { it.isRunning() && it.serverName == FakePhpServers.OTHER }
                assertTrue("disabled" !in servers.saved())
                awaitTag(AppTags.extension("servers:php")).assertTextContains("On", substring = true)
            } finally {
                servers.delete()
                php.delete()
            }
        }

    @Test
    fun `a server shows the link to its GitHub page, which opens in the browser`() =
        runComposeUiTest {
            val servers = FakePhpServers().chosen()
            val php = FakePhpServers.project()
            try {
                val app = launchWithProject(php, languageServers = servers.settings)
                val opened = ArrayList<String>()
                app.project.openUrl = { opened += it }
                openExtensions()
                openCard("servers:php")

                awaitTag(AppTags.settingsServerLink(FakePhpServers.FAKE)).performClick()
                awaitCondition { opened == listOf(FakePhpServers.FAKE_HOMEPAGE) }
                assertEquals("github.com/example/fakephp", textOf(AppTags.settingsServerLink(FakePhpServers.FAKE)))
            } finally {
                servers.delete()
                php.delete()
            }
        }
}
