package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

// The budgets are several times what a developer machine measures: the runners are slow, a regression is not.
private const val PLUGIN_CARD_PREFIX = "extensions.card:plugin:"
private const val PLUGINS = 300
private const val TABS = 40
private const val OPEN_MS = 60_000L
private const val OPEN_MB = 3_000L
private const val SEARCH_MS = 60_000L
private const val SEARCH_MB = 3_000L
private const val TABS_MS = 120_000L
private const val TABS_MB = 4_000L
private const val SERVERS_MS = 120_000L
private const val TOGGLE_MS = 180_000L

/** The extensions window with a folder of many plugins: opening it, searching, opening tabs, turning one off and on. */
@OptIn(ExperimentalTestApi::class)
class ExtensionsPerformanceE2eTest {
    private val servers = FakePhpServers().chosen()
    private val php = FakePhpServers.project()
    private val plugins: File = Files.createTempDirectory("ide-plugins").toFile()

    @AfterTest
    fun cleanup() {
        servers.delete()
        php.delete()
        plugins.deleteRecursively()
    }

    // Folders with a manifest and no code: they are listed, as off, which is all the window has to show.
    private fun manyPlugins() {
        for (index in 0 until PLUGINS) {
            val id = "plugin-$index"
            val folder = File(plugins, id).apply { mkdirs() }
            val manifest =
                """
                id: $id
                version: 1.$index
                apiVersion: $API_VERSION
                client: none.$id
                """.trimIndent()
            File(folder, "plugin.yaml").writeText(manifest)
        }
    }

    private fun ComposeUiTest.pluginCards(): Int {
        val isPluginCard =
            SemanticsMatcher("a plugin card") { node ->
                val tag = node.config.getOrNull(SemanticsProperties.TestTag)
                tag?.startsWith(PLUGIN_CARD_PREFIX) == true
            }
        return onAllNodes(isPluginCard).fetchSemanticsNodes().size
    }

    private fun ComposeUiTest.launch(): E2eApp =
        launchWithProject(php, pluginsDirectory = plugins, languageServers = servers.settings)

    private fun ComposeUiTest.showWindow() {
        onNodeWithTag(AppTags.TOOL_EXTENSIONS).performClick()
        awaitTag(AppTags.EXTENSIONS)
    }

    @Test
    fun `a folder of 300 plugins opens in the window within its budget`() =
        runComposeUiTest {
            manyPlugins()
            launch()
            measure("open the extensions window, $PLUGINS plugins", OPEN_MS, OPEN_MB) {
                showWindow()
                awaitTag(AppTags.extension("plugin:plugin-0"))
                awaitTag(AppTags.extension("servers:php"), timeout = SERVER_START_MS)
                renderFrame()
            }
            assertEquals(PLUGINS, pluginCards())
        }

    @Test
    fun `typing in the search narrows 300 plugins to a handful, within its budget`() =
        runComposeUiTest {
            manyPlugins()
            launch()
            showWindow()
            awaitTag(AppTags.extension("plugin:plugin-0"))

            measure("search plugin-29, key by key", SEARCH_MS, SEARCH_MB) {
                for (letter in "plugin-29") {
                    onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextInput(letter.toString())
                    renderFrame()
                }
            }
            // plugin-29 and plugin-290 to plugin-299: what keeps it fast is that the rest is not composed.
            awaitCondition { pluginCards() == 11 }
            measure("clear the search, $PLUGINS plugins back", SEARCH_MS, SEARCH_MB) {
                onNodeWithTag(AppTags.EXTENSIONS_SEARCH).performTextClearance()
                awaitCondition { pluginCards() == PLUGINS }
                renderFrame()
            }
        }

    @Test
    fun `forty extension tabs open and close within their budget, none left behind`() =
        runComposeUiTest {
            manyPlugins()
            val app = launch()
            showWindow()
            awaitTag(AppTags.extension("plugin:plugin-0"))

            measure("open $TABS extension tabs", TABS_MS, TABS_MB) {
                for (index in 0 until TABS) {
                    onNodeWithTag(AppTags.extension("plugin:plugin-$index")).performScrollTo().performClick()
                    awaitTag(EditorTags.extensionTab("plugin:plugin-$index"))
                }
                renderFrame()
            }
            // The close buttons of a strip this long are out of sight: the tabs are closed as Close All does.
            val editor = app.project.editor
            measure("close $TABS extension tabs", TABS_MS, TABS_MB) {
                editor.tabs.toList().forEach { editor.close(it) }
                renderFrame()
            }
            assertTrue(editor.tabs.isEmpty())
            awaitNoTag(EditorTags.extensionTab("plugin:plugin-0"))
        }

    @Test
    fun `the window asks the server for its servers once, however often it is shown again`() =
        runComposeUiTest {
            val app = launch()
            measure("list the language servers", SERVERS_MS) {
                showWindow()
                awaitTag(AppTags.extension("servers:php"), timeout = SERVER_START_MS)
            }
            val asked = app.localLanguageServers.calls.count { it.method == "choice" }
            assertTrue(asked > 0)

            repeat(5) {
                onNodeWithTag(AppTags.EXTENSIONS_HIDE).performClick()
                awaitNoTag(AppTags.EXTENSIONS)
                showWindow()
                awaitTag(AppTags.extension("servers:php"))
            }
            // A tab asks nothing more either.
            onNodeWithTag(AppTags.extension("servers:php")).performClick()
            awaitTag(EditorTags.extensionTab("servers:php"))
            assertEquals(asked, app.localLanguageServers.calls.count { it.method == "choice" })
        }

    @Test
    fun `a plugin turned off and on five times stays within its budget`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "todo", API_VERSION, "")
            launch()
            showWindow()
            awaitTag(AppTags.extension("plugin:todo")).performClick()
            awaitTag(AppTags.pluginToggle("todo"))

            measure("turn a plugin off and on, 5 times", TOGGLE_MS) {
                repeat(5) {
                    onNodeWithTag(AppTags.pluginToggle("todo")).performClick()
                    awaitCondition { toggleLabel() == "Enable" }
                    onNodeWithTag(AppTags.pluginToggle("todo")).performClick()
                    awaitCondition { toggleLabel() == "Disable" }
                }
            }
        }

    private fun ComposeUiTest.toggleLabel(): String {
        val node = onNodeWithTag(AppTags.pluginToggle("todo")).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        return texts.joinToString(" ") { it.text }
    }
}
