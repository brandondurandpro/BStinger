package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Setting
import org.example.kotlineditor.state.SettingsCategory
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test

// Files of the project, and lines of the file open under the dialog: the dialog must not cost what they cost.
private const val FILES = 2_000
private const val LINES = 20_000
private const val ROUNDS = 10

// Each step takes a few tens of ms here: the budgets leave room for a slow runner, not for a cost per file or line.
private const val OPEN_BUDGET_MS = 3_000L
private const val CATEGORIES_BUDGET_MS = 5_000L
private const val SEARCH_BUDGET_MS = 3_000L
private const val BUDGET_MB = 400L

/**
 * The Settings dialog over a large project (2,000 files, a 20,000-line file open): opened and closed, every
 * category shown, a search typed key by key, each repeated, within budgets that a cost per file or per line breaks.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            (1..FILES).forEach { file("src/pkg${it % 40}/File$it.kt", "val value$it = $it\n") }
            file("Large.kt", (1..LINES).joinToString("\n", postfix = "\n") { "val line$it = $it" })
        }

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the settings open, switch category and search fast over a large project`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("Large.kt")
            awaitEditorReady()
            measure("Settings opened and closed x$ROUNDS", OPEN_BUDGET_MS, BUDGET_MB) {
                repeat(ROUNDS) {
                    onNodeWithTag(AppTags.PROJECT).performKeyInput {
                        withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.S) } }
                    }
                    onNodeWithTag(AppTags.setting(Setting.FontSize.name)).assertExists()
                    renderFrame()
                    onNodeWithTag(AppTags.SETTINGS_SEARCH).performKeyInput { pressKey(Key.Escape) }
                    awaitNoTag(AppTags.SETTINGS)
                }
            }
            onNodeWithTag(AppTags.PROJECT_WIDGET).performClick()
            awaitTag(AppTags.PROJECT_SETTINGS).performClick()
            awaitTag(AppTags.SETTINGS)
            measure("Every settings category shown x$ROUNDS", CATEGORIES_BUDGET_MS, BUDGET_MB) {
                repeat(ROUNDS) {
                    for (category in SettingsCategory.entries) {
                        onNodeWithTag(AppTags.settingsCategory(category.name)).performClick()
                        // The click waits for the frame: the settings of the category must be there, not later.
                        onNodeWithTag(
                            AppTags.setting(Setting.entries.first { it.category == category }.name),
                        ).assertExists()
                        renderFrame()
                    }
                }
            }
            measure("A search typed key by key x$ROUNDS", SEARCH_BUDGET_MS, BUDGET_MB) {
                repeat(ROUNDS) {
                    onNodeWithTag(AppTags.SETTINGS_SEARCH).performTextReplacement("")
                    for (letter in "inlay") {
                        onNodeWithTag(AppTags.SETTINGS_SEARCH).performTextInput(letter.toString())
                        renderFrame()
                    }
                    onNodeWithTag(AppTags.setting(Setting.InlayHints.name)).assertExists()
                    onNodeWithTag(AppTags.setting(Setting.FontSize.name)).assertDoesNotExist()
                }
            }
        }
}
