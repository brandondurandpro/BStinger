package org.example.kotlineditor.e2e

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
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
import org.example.kotlineditor.ui.TreeTags
import org.example.kotlineditor.ui.binding
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

private const val PLUGINS = 50
private const val KEY_PRESSES = 100
private const val FRAMES = 100

private fun buttonOf(index: Int) = "perf.plugin.$index.button"

// A plugin like the ones a user would stack: a tool window, and two shortcuts (the last one is the one pressed).
private fun fakePlugin(index: Int, hits: IntArray): IdePlugin {
    val window =
        ToolWindowContribution(
            defaults = ToolWindowDefaults(ToolWindow("perf.plugin.$index"), Dock.Bottom),
            tag = buttonOf(index),
            group = StripGroup.Bottom,
            icon = { Box(Modifier) },
            content = { _, _, modifier -> Box(modifier) },
        )
    val keys = listOf(Key.F1, Key.F2, Key.F3, Key.F4, Key.F5, Key.F6, Key.F7, Key.F8, Key.F9)
    val first = binding<ActionContext>(setOf(keys[index % keys.size]), ctrl = true, shift = true, alt = true) {}
    val second = binding<ActionContext>(setOf(Key.Q), ctrl = true, shift = true, alt = true) { hits[0]++ }
    return IdePlugin("perf-$index", listOf(window), listOf(first, second))
}

@OptIn(ExperimentalTestApi::class)
class PluginsPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `fifty plugins start, show their buttons, take keys and render frames within budget`() =
        runComposeUiTest {
            val hits = IntArray(1)
            val fifty = List(PLUGINS) { fakePlugin(it, hits) }
            measure("plugins.start with $PLUGINS plugins", budgetMillis = 10_000, budgetMegabytes = 600) {
                launchWithProject(project, plugins = fifty)
            }
            val buttons = onAllNodesWithTagPrefix("perf.plugin.")
            assertEquals(PLUGINS, buttons)

            onNodeWithTag(TreeTags.TREE).performClick()
            measure("plugins.keys $KEY_PRESSES shortcuts", budgetMillis = 5_000, budgetMegabytes = 150) {
                repeat(KEY_PRESSES) { pressPluginShortcut() }
            }
            assertEquals(KEY_PRESSES, hits[0])

            measure("plugins.frames $FRAMES frames", budgetMillis = 2_000, budgetMegabytes = 100) {
                repeat(FRAMES) { renderFrame() }
            }
        }

    private fun ComposeUiTest.pressPluginShortcut() {
        onNodeWithTag(TreeTags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) {
                withKeyDown(Key.ShiftLeft) { withKeyDown(Key.AltLeft) { pressKey(Key.Q) } }
            }
        }
    }

    private fun ComposeUiTest.onAllNodesWithTagPrefix(prefix: String): Int {
        val matcher = hasTestTagStartingWith(prefix)
        return onAllNodes(matcher).fetchSemanticsNodes().size
    }

    private fun hasTestTagStartingWith(prefix: String) =
        SemanticsMatcher("tag starts with $prefix") { node ->
            val tag = node.config.getOrNull(SemanticsProperties.TestTag)
            tag != null && tag.startsWith(prefix) && tag.endsWith(".button")
        }
}
