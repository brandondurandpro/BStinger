package org.example.kotlineditor.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.Dock
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowDefaults
import org.example.kotlineditor.ui.ActionRegistry
import org.example.kotlineditor.ui.ActionScope
import org.example.kotlineditor.ui.StripGroup
import org.example.kotlineditor.ui.ToolWindowContribution
import org.example.kotlineditor.ui.ToolWindowRegistry
import org.example.kotlineditor.ui.TreeTags
import org.example.kotlineditor.ui.binding
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
            icon = { Box(Modifier) },
            content = { _, _, modifier -> Box(modifier.testTag(PANEL)) },
        )

    @Test
    fun `a tool window registered by a plugin gets a button and a panel, and leaves with the plugin`() =
        runComposeUiTest {
            registrations += ToolWindowRegistry.default.register(listOf(todoWindow()))
            launchWithProject(project)

            awaitTag(BUTTON).performClick()
            awaitTag(PANEL)

            registrations.removeLast().close()
            awaitNoTag(BUTTON)
            awaitNoTag(PANEL)
        }

    @Test
    fun `a shortcut registered by a plugin runs its action, and stops with the plugin`() =
        runComposeUiTest {
            var runs = 0
            val shortcut = binding<org.example.kotlineditor.ui.IdeContext>(setOf(Key.J), ctrl = true, alt = true) { runs++ }
            val registration = ActionRegistry.default.register(ActionScope.Ide, listOf(shortcut))
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
