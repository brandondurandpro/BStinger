package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.SettingsCategory
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What the Settings dialog shows when the network or the server fails while it lists or switches the PHP language
 * servers: nothing stays waiting, a switch happens once, the list comes back with the server.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsChaosE2eTest {
    private val project = FakePhpServers.project()
    private val servers = FakePhpServers(alternative = true).chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    private fun ComposeUiTest.launch(): E2eApp {
        val app = launchWithProject(project, chaos = true, languageServers = servers.settings)
        openFromTree("src/index.php")
        awaitServer(app) { it.isRunning() }
        return app
    }

    private fun ComposeUiTest.openServers() {
        onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
        awaitTag(AppTags.PROJECT_SETTINGS).performClick()
        awaitTag(AppTags.settingsCategory(SettingsCategory.LanguageServers.name)).performClick()
    }

    private fun ComposeUiTest.awaitSwitchedToOther(app: E2eApp) {
        awaitTag(AppTags.settingsServerUse(FakePhpServers.FAKE))
        awaitNoTag(AppTags.settingsServerUse(FakePhpServers.OTHER))
        awaitServer(app) { it.isRunning() && it.serverName == FakePhpServers.OTHER }
        assertEquals("php=${FakePhpServers.OTHER}", servers.saved().lines().last { it.startsWith("php") })
    }

    @Test
    fun `opened while the network is down, the dialog says it is asking, then lists the servers once it is back`() =
        runComposeUiTest {
            val app = launch()
            app.network.goDown()
            awaitReconnecting()
            openServers()
            awaitTag(AppTags.SETTINGS_SERVERS_LOADING)
            // The other settings do not wait for the server.
            awaitTag(AppTags.settingsCategory(SettingsCategory.Editor.name)).performClick()
            awaitTag(AppTags.SETTINGS_FONT_LARGER)
            app.network.comeBack()
            awaitReconnected()
            awaitTag(AppTags.settingsCategory(SettingsCategory.LanguageServers.name)).performClick()
            awaitTag(AppTags.settingsServerUse(FakePhpServers.OTHER))
            awaitNoTag(AppTags.SETTINGS_SERVERS_LOADING)
        }

    @Test
    fun `a switch asked while the network is down shows it is switching, and happens once it is back`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launch()
            openServers()
            awaitTag(AppTags.settingsServerUse(FakePhpServers.OTHER))
            app.network.goDown()
            awaitReconnecting()
            onNodeWithTag(AppTags.settingsServerUse(FakePhpServers.OTHER)).performClick()
            awaitNoTag(AppTags.settingsServerUse(FakePhpServers.OTHER))
            // Waiting, not pretending: the server in use is still the first one.
            awaitTag(AppTags.settingsServer(FakePhpServers.FAKE))
            assertTrue("php=${FakePhpServers.FAKE}" in servers.saved())
            app.network.comeBack()
            awaitReconnected()
            awaitSwitchedToOther(app)
        }

    @Test
    fun `a switch whose answer was lost is not run twice, and shows as done once reconnected`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launch()
            openServers()
            awaitTag(AppTags.settingsServerUse(FakePhpServers.OTHER))
            app.network.dropFromServer = true
            onNodeWithTag(AppTags.settingsServerUse(FakePhpServers.OTHER)).performClick()
            awaitCondition { "php=${FakePhpServers.OTHER}" in servers.saved() }
            app.network.cutAll()
            app.network.dropFromServer = false
            awaitReconnected()
            awaitSwitchedToOther(app)
            // The other server started once: the replayed request did not restart it.
            assertEquals(1, app.languageServers.count { it.serverName == FakePhpServers.OTHER })
        }

    @Test
    fun `after a restart of the server, the dialog opened again lists the servers and switches`() =
        runComposeUiTest(testTimeout = 3.minutes) {
            val app = launch()
            app.process.restart()
            awaitReconnected()
            openServers()
            awaitTag(AppTags.settingsServerUse(FakePhpServers.OTHER)).performClick()
            awaitSwitchedToOther(app)
            awaitNoTag(AppTags.SETTINGS_SERVERS_ERROR)
        }
}
