package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.FilePreferenceStore
import org.example.kotlineditor.state.InlayHintsMode
import org.example.kotlineditor.state.PreferenceKeys
import org.example.kotlineditor.state.Setting
import org.example.kotlineditor.state.SettingsCategory
import org.example.kotlineditor.ui.AppTags
import org.example.kotlineditor.ui.LanguageServerTags
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Settings dialog: opened from Ctrl+Alt+S, the project menu or the welcome screen, every setting in its
 * category or found by a search, each change applied at once and kept, the PHP language server switched.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsE2eTest {
    private val project = SampleProject.standard()
    private val dir = Files.createTempDirectory("kotlin-editor-settings").toFile()
    private val created = ArrayList<FakePhpServers>()

    @AfterTest
    fun cleanup() {
        project.delete()
        dir.deleteRecursively()
        created.forEach { it.delete() }
    }

    private fun preferences() = FilePreferenceStore(dir.resolve("preferences.properties"))

    private fun ComposeUiTest.openSettingsFromMenu() {
        onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
        awaitTag(AppTags.PROJECT_SETTINGS).performClick()
        awaitTag(AppTags.SETTINGS)
    }

    private fun ComposeUiTest.category(category: SettingsCategory) {
        onNodeWithTag(AppTags.settingsCategory(category.name)).performClick()
        onNodeWithTag(AppTags.settingsCategory(category.name)).assertIsSelected()
    }

    private fun ComposeUiTest.php(servers: FakePhpServers): E2eApp {
        created += servers
        val php = FakePhpServers.project()
        val app = launchWithProject(php, preferences(), languageServers = servers.settings)
        openFromTree("src/index.php")
        return app
    }

    @Test
    fun `Ctrl+Alt+S and the project menu open the settings, Escape and the close button close them`() =
        runComposeUiTest {
            launchWithProject(project)
            onNodeWithTag(AppTags.PROJECT).performKeyInput {
                withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.S) } }
            }
            awaitTag(AppTags.SETTINGS)
            onNodeWithTag(AppTags.SETTINGS_TITLE).assertTextEquals(SettingsCategory.Editor.title)
            onNodeWithTag(AppTags.SETTINGS_SEARCH).performKeyInput { pressKey(Key.Escape) }
            awaitNoTag(AppTags.SETTINGS)
            openSettingsFromMenu()
            onNodeWithTag(AppTags.SETTINGS_CLOSE).performClick()
            awaitNoTag(AppTags.SETTINGS)
        }

    @Test
    fun `each category shows its settings, and every setting is in one`() =
        runComposeUiTest {
            launchWithProject(project)
            openSettingsFromMenu()
            for (category in SettingsCategory.entries) {
                category(category)
                onNodeWithTag(AppTags.SETTINGS_TITLE).assertTextEquals(category.title)
                for (setting in Setting.entries) {
                    if (setting.category == category) {
                        awaitTag(
                            AppTags.setting(setting.name),
                        )
                    } else {
                        awaitNoTag(AppTags.setting(setting.name))
                    }
                }
            }
        }

    @Test
    fun `the search finds settings in every category, and says when none matches`() =
        runComposeUiTest {
            launchWithProject(project)
            openSettingsFromMenu()
            onNodeWithTag(AppTags.SETTINGS_SEARCH).performTextInput("phpactor")
            awaitTag(AppTags.setting(Setting.PhpServer.name))
            onNodeWithTag(AppTags.SETTINGS_TITLE).assertTextEquals("Search results")
            awaitNoTag(AppTags.setting(Setting.FontSize.name))
            // Words of the title and of the category, in any order.
            onNodeWithTag(AppTags.SETTINGS_SEARCH).performTextReplacement("view always")
            awaitTag(AppTags.setting(Setting.AutoSelect.name))
            awaitNoTag(AppTags.setting(Setting.PhpServer.name))
            onNodeWithTag(AppTags.SETTINGS_SEARCH).performTextReplacement("nothing like this")
            awaitTag(AppTags.SETTINGS_EMPTY)
            // A category clears the search.
            category(SettingsCategory.Privacy)
            awaitTag(AppTags.setting(Setting.Telemetry.name))
            awaitNoTag(AppTags.SETTINGS_EMPTY)
        }

    @Test
    fun `the font size changes the editors at once and is kept for the next session`() {
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            openFromTree("README.md")
            openSettingsFromMenu()
            onNodeWithTag(AppTags.SETTINGS_FONT_SIZE).assertTextEquals("13 pt")
            onNodeWithTag(AppTags.SETTINGS_FONT_LARGER).performClick()
            onNodeWithTag(AppTags.SETTINGS_FONT_LARGER).performClick()
            onNodeWithTag(AppTags.SETTINGS_FONT_SMALLER).performClick()
            awaitCondition { app.state.editorFontSize == 14f }
            onNodeWithTag(AppTags.SETTINGS_FONT_SIZE).assertTextEquals("14 pt")
            awaitCondition { preferences().load()[PreferenceKeys.EDITOR_FONT_SIZE] == "14.0" }
        }
        runComposeUiTest {
            val app = launchApp(preferences = preferences())
            assertEquals(14f, app.state.editorFontSize)
            onNodeWithTag(AppTags.WELCOME_SETTINGS).performClick()
            awaitTag(AppTags.SETTINGS_FONT_RESET).performClick()
            awaitCondition { app.state.editorFontSize == 13f }
        }
    }

    @Test
    fun `the project settings change the open project at once`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            openSettingsFromMenu()
            onNodeWithTag(AppTags.settingsInlayHints(InlayHintsMode.Inline.name)).performClick()
            onNodeWithTag(AppTags.settingsInlayHints(InlayHintsMode.Inline.name)).assertIsSelected()
            assertEquals(InlayHintsMode.Inline, app.project.inlayHintsMode)
            category(SettingsCategory.ProjectView)
            val autoSelect = AppTags.settingToggle(Setting.AutoSelect.name)
            onNodeWithTag(autoSelect).assertIsOn().performClick()
            onNodeWithTag(autoSelect).assertIsOff()
            assertFalse(app.project.autoSelectOpenedFile)
            awaitCondition {
                val saved = preferences().load()
                saved[PreferenceKeys.INLAY_HINTS] == "Inline" &&
                    saved[PreferenceKeys.AUTO_SELECT_OPENED_FILE] == "false"
            }
        }

    @Test
    fun `on the welcome screen, the project settings changed apply to the next project opened`() =
        runComposeUiTest {
            val app = launchApp(preferences = preferences())
            onNodeWithTag(AppTags.WELCOME_SETTINGS).performClick()
            awaitTag(AppTags.settingsInlayHints(InlayHintsMode.Off.name)).performClick()
            onNodeWithTag(AppTags.settingsInlayHints(InlayHintsMode.Off.name)).assertIsSelected()
            category(SettingsCategory.ProjectView)
            onNodeWithTag(AppTags.settingToggle(Setting.AutoSelect.name)).performClick()
            onNodeWithTag(AppTags.settingToggle(Setting.AutoSelect.name)).assertIsOff()
            onNodeWithTag(AppTags.SETTINGS_CLOSE).performClick()
            awaitNoTag(AppTags.SETTINGS)
            openProjectDirectoryFromWelcome(project.path)
            awaitCondition { app.state.screen is org.example.kotlineditor.state.Screen.Project }
            assertEquals(InlayHintsMode.Off, app.project.inlayHintsMode)
            assertFalse(app.project.autoSelectOpenedFile)
        }

    @Test
    fun `agent mode and the performance measures are switched from their categories`() =
        runComposeUiTest {
            val app = launchWithProject(project, preferences())
            openSettingsFromMenu()
            category(SettingsCategory.Experimental)
            onNodeWithTag(AppTags.settingToggle(Setting.AgentMode.name)).assertIsOff().performClick()
            awaitTag(AppTags.MODE_SWITCH)
            category(SettingsCategory.Privacy)
            onNodeWithTag(AppTags.settingToggle(Setting.Telemetry.name)).assertIsOff().performClick()
            onNodeWithTag(AppTags.settingToggle(Setting.Telemetry.name)).assertIsOn()
            assertTrue(app.state.telemetry.enabled)
            awaitCondition {
                val saved = preferences().load()
                saved[PreferenceKeys.AGENT_MODE] == "true" && saved[PreferenceKeys.TELEMETRY_ENABLED] == "true"
            }
        }

    @Test
    fun `the PHP server is switched from the settings, the running one replaced and the choice kept`() =
        runComposeUiTest {
            val servers = FakePhpServers(alternative = true).chosen()
            val app = php(servers)
            val before = awaitServer(app) { it.isRunning() }
            assertEquals(FakePhpServers.FAKE, before.serverName)
            openSettingsFromMenu()
            category(SettingsCategory.LanguageServers)
            awaitTag(AppTags.settingsServer(FakePhpServers.FAKE))
            awaitNoTag(AppTags.settingsServerUse(FakePhpServers.FAKE))
            // A server that cannot run here cannot be chosen.
            onNodeWithTag(AppTags.settingsServerUse(FakePhpServers.NEEDS_NODE)).assertIsNotEnabled()
            onNodeWithTag(AppTags.settingsServerUse(FakePhpServers.OTHER)).performClick()
            awaitNoTag(AppTags.settingsServerUse(FakePhpServers.OTHER))
            awaitTag(AppTags.settingsServerUse(FakePhpServers.FAKE))
            awaitServer(app) { it.isRunning() && it.serverName == FakePhpServers.OTHER }
            awaitCondition { app.languageServers.none { it.serverName == FakePhpServers.FAKE } }
            assertTrue("php=${FakePhpServers.OTHER}" in servers.saved())
            awaitNoTag(AppTags.SETTINGS_SERVERS_ERROR)
        }

    @Test
    fun `a PHP server chosen in the settings answers the banner that asked for one`() =
        runComposeUiTest {
            val servers = FakePhpServers()
            val app = php(servers)
            awaitTag(LanguageServerTags.LSP_CHOICE, timeout = SERVER_START_MS)
            openSettingsFromMenu()
            category(SettingsCategory.LanguageServers)
            awaitTag(AppTags.settingsServerUse(FakePhpServers.FAKE)).performClick()
            awaitNoTag(LanguageServerTags.LSP_CHOICE)
            awaitServer(app) { it.isRunning() && it.serverName == FakePhpServers.FAKE }
        }
}
