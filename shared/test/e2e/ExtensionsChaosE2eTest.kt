package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
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

private const val PHP_CARD = "servers:php"

/** The extensions window and its tabs when the network or the server fails, or when the disk changes under them. */
@OptIn(ExperimentalTestApi::class)
class ExtensionsChaosE2eTest {
    private val servers = FakePhpServers(alternative = true).chosen(FakePhpServers.OTHER)
    private val php = FakePhpServers.project()
    private val plugins: File = Files.createTempDirectory("ide-plugins").toFile()

    @AfterTest
    fun cleanup() {
        servers.delete()
        php.delete()
        plugins.deleteRecursively()
    }

    private fun ComposeUiTest.launch(): E2eApp =
        launchWithProject(php, chaos = true, pluginsDirectory = plugins, languageServers = servers.settings)

    private fun ComposeUiTest.openExtensions() {
        onNodeWithTag(AppTags.TOOL_EXTENSIONS).performClick()
        awaitTag(AppTags.EXTENSIONS)
    }

    private fun ComposeUiTest.openPhpTab() {
        awaitTag(AppTags.extension(PHP_CARD)).performClick()
        awaitTag(EditorTags.extensionTab(PHP_CARD))
    }

    private fun ComposeUiTest.textOf(tag: String): String {
        val node = onNodeWithTag(tag).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty()
        return texts.joinToString(" ") { it.text }
    }

    @Test
    fun `a window opened while the network is down shows no false empty list, then the servers once back`() =
        runComposeUiTest {
            val app = launch()
            app.network.goDown()
            awaitReconnecting()
            openExtensions()

            // The request waits for the link: nothing pretends there is no extension, nothing shows a card yet.
            awaitNoTag(AppTags.EXTENSIONS_EMPTY)
            awaitNoTag(AppTags.extension(PHP_CARD))

            app.network.comeBack()
            awaitReconnected()
            awaitTag(AppTags.extension(PHP_CARD))
            awaitNoTag(AppTags.EXTENSIONS_ERROR)
        }

    @Test
    fun `a server turned off while the network is down is turned off once, never stuck switching`() =
        runComposeUiTest {
            val app = launch()
            openExtensions()
            openPhpTab()
            awaitTag(AppTags.settingsServerToggle("php"))

            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
            app.network.comeBack()
            awaitReconnected()

            // Either the request went through after the retry, or it failed and said so: the button works again.
            awaitCondition(timeout = SERVER_START_MS) {
                val label = textOf(AppTags.settingsServerToggle("php"))
                val failed = onAllNodesWithTagExists(AppTags.EXTENSIONS_ERROR)
                label == "Enable" || failed
            }
            onNodeWithTag(AppTags.settingsServerToggle("php")).assertIsEnabled()
        }

    private fun ComposeUiTest.onAllNodesWithTagExists(tag: String): Boolean =
        onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `the answer lost after the server ran the request does not turn the server off twice`() =
        runComposeUiTest {
            val app = launch()
            openExtensions()
            openPhpTab()
            awaitTag(AppTags.settingsServerToggle("php"))

            app.network.dropFromServer = true
            onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
            awaitCondition { servers.saved().contains("disabled") }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()

            // Turning it on again after the reconnection brings back the server chosen, once.
            awaitCondition(timeout = SERVER_START_MS) {
                val label = textOf(AppTags.settingsServerToggle("php"))
                label == "Enable" || label == "Disable"
            }
            if (textOf(AppTags.settingsServerToggle("php")) == "Enable") {
                onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
            }
            awaitCondition(timeout = SERVER_START_MS) { textOf(AppTags.settingsServerToggle("php")) == "Disable" }
            val saved = servers.saved()
            assertTrue("disabled" !in saved, saved)
            assertEquals(1, Regex("php=").findAll(saved).count(), saved)
        }

    @Test
    fun `a server turned off stays off after the server restarts, and no server starts for a php file`() =
        runComposeUiTest {
            val app = launch()
            openFromTree("src/index.php")
            awaitServer(app) { it.isRunning() }
            openExtensions()
            openPhpTab()
            onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
            awaitCondition { textOf(AppTags.settingsServerToggle("php")) == "Enable" }

            app.process.restart()
            awaitReconnected()
            // The tab reloads its servers on the new connection: still off.
            awaitCondition(timeout = SERVER_START_MS) { textOf(AppTags.settingsServerToggle("php")) == "Enable" }
            assertTrue(app.languageServers.none { it.language == "php" })

            onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
            awaitServer(app) { it.isRunning() && it.serverName == FakePhpServers.OTHER }
        }

    @Test
    fun `a slow link keeps the window responsive, and a second click does not switch twice`() =
        runComposeUiTest {
            val app = launch()
            openExtensions()
            openPhpTab()
            awaitTag(AppTags.settingsServerToggle("php"))

            app.network.latencyMs = SLOW_MS
            onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
            // The window still answers while the request is in flight.
            onNodeWithTag(AppTags.EXTENSIONS_SEARCH).assertExists()
            onNodeWithTag(AppTags.settingsServerToggle("php")).performClick()
            app.network.latencyMs = 0
            awaitCondition(timeout = SERVER_START_MS) { textOf(AppTags.settingsServerToggle("php")) == "Enable" }
            val saved = servers.saved()
            assertTrue("disabled" in saved, saved)
        }

    @Test
    fun `a silent link leaves the cards as they were, with nothing stuck loading`() =
        runComposeUiTest {
            val app = launch()
            openExtensions()
            awaitTag(AppTags.extension(PHP_CARD))

            app.network.blackHole = true
            awaitReconnecting()
            awaitTag(AppTags.extension(PHP_CARD))
            awaitNoTag(AppTags.EXTENSIONS_EMPTY)

            app.network.blackHole = false
            app.network.cutAll()
            awaitReconnected()
            awaitTag(AppTags.extension(PHP_CARD))
        }

    @Test
    fun `a plugin deleted from the disk while its tab is open leaves a note and no card`() =
        runComposeUiTest {
            PluginFixture.create(plugins, "todo", API_VERSION, "")
            val app = launch()
            openExtensions()
            awaitTag(AppTags.extension("plugin:todo")).performClick()
            awaitTag(EditorTags.extensionTab("plugin:todo"))
            awaitTag(AppTags.pluginToggle("todo"))

            File(plugins, "todo").deleteRecursively()
            val installed = checkNotNull(app.installedPlugins)
            installed.start()

            awaitNoTag(AppTags.extension("plugin:todo"))
            awaitNoTag(AppTags.pluginToggle("todo"))
        }

    private companion object {
        const val SLOW_MS = 400L
    }
}
