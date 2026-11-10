package org.example.kotlineditor.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.plugin.ActionContext
import org.example.kotlineditor.plugin.IdePlugin
import org.example.kotlineditor.plugin.StripGroup
import org.example.kotlineditor.plugin.ToolWindowContribution
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.ui.Lucide
import org.example.kotlineditor.ui.TreeTags
import org.example.kotlineditor.ui.binding
import org.example.kotlineditor.ui.loadPlugin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val BUTTON = "plugin.todo.button"
private const val PANEL = "plugin.todo.panel"

@OptIn(ExperimentalTestApi::class)
class PluginContributionsE2eTest {
    private val project = SampleProject.standard()
    private val registrations = mutableListOf<AutoCloseable>()

    @AfterTest
    fun cleanup() {
        registrations.forEach { it.close() }
        project.delete()
    }

    private fun todoWindow() =
        ToolWindowContribution(
            defaults = ToolWindowDefaults(ToolWindow("plugin.todo"), Dock.Bottom),
            tag = BUTTON,
            group = StripGroup.Bottom,
            icon = Lucide.Folder,
            content = { _, _, modifier -> Box(modifier.testTag(PANEL)) },
        )

    @Test
    fun `a tool window registered by a plugin gets a button and a panel, and leaves with the plugin`() =
        runComposeUiTest {
            val todo = IdePlugin("todo", toolWindows = listOf(todoWindow()))
            val registration = loadPlugin(todo)
            registrations += registration
            launchWithProject(project)

            awaitTag(BUTTON).performClick()
            awaitTag(PANEL)

            registration.close()
            awaitNoTag(BUTTON)
            awaitNoTag(PANEL)
        }

    @Test
    fun `a shortcut registered by a plugin runs its action, and stops with the plugin`() =
        runComposeUiTest {
            var runs = 0
            val shortcut =
                binding<ActionContext>(
                    setOf(Key.J),
                    ctrl = true,
                    alt = true,
                ) { runs++ }
            val registration = loadPlugin(IdePlugin("keys", shortcuts = listOf(shortcut)))
            registrations += registration
            launchWithProject(project)
            onNodeWithTag(TreeTags.TREE).performClick()

            fun press() =
                onNodeWithTag(TreeTags.TREE).performKeyInput {
                    withKeyDown(Key.CtrlLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.J) } }
                }
            press()
            awaitCondition { runs == 1 }

            registration.close()
            press()
            waitForIdle()
            assertEquals(1, runs)
        }
}
