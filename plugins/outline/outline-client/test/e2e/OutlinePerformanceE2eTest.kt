package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.OutlineTags
import kotlin.test.AfterTest
import kotlin.test.Test

private const val DECLARATIONS = 3_000

/**
 * The Outline window on a big input: a file of [DECLARATIONS] functions, its outline computed and its list shown,
 * then a jump to a declaration. Each step prints a `PERF` line and has a budget several times what a developer
 * machine measures, so a slow runner passes but an algorithmic regression fails.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinePerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    @Test
    fun `the outline of three thousand declarations shows and jumps within its budgets`() =
        runComposeUiTest {
            project.file("Big.kt", declarationsFile())
            val app = launchWithProject(project)
            openFromTree("Big.kt")

            measure("outline ${DECLARATIONS} declarations and show the list", budgetMillis = 3_000, budgetMegabytes = 60) {
                awaitTag(OutlineTags.TOOL_BUTTON).performClick()
                awaitTag(OutlineTags.item("demo0", 1))
                renderFrame()
            }
            measure("jump to a declaration from the outline", budgetMillis = 1_000, budgetMegabytes = 20) {
                onNodeWithTag(OutlineTags.item("demo0", 1)).performClick()
                awaitCondition { app.project.editor.activeTab?.document?.caret == TextPos(1, 4) }
                renderFrame()
            }
        }

    // One `package` line, then [DECLARATIONS] top-level functions, one per line.
    private fun declarationsFile(): String {
        val lines = buildList {
            add("package demo")
            repeat(DECLARATIONS) { index -> add("fun demo$index() {}") }
        }
        return textOf(*lines.toTypedArray())
    }
}
