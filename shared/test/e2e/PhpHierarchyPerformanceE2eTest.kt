package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.ui.EditorTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val CALLERS = 2_000

/** The call hierarchy of a PHP function called by 2,000 functions. */
@OptIn(ExperimentalTestApi::class)
class PhpHierarchyPerformanceE2eTest {
    private val project =
        SampleProject().apply {
            file("composer.json", "{}\n")
            val callers = (1..CALLERS).flatMap { listOf("function caller$it()", "{", "    return target();", "}") }
            file(
                "src/big.php",
                (listOf("<?php", "function target()", "{", "    return 1;", "}") + callers + "").joinToString("\n"),
            )
        }
    private val servers = FakePhpServers().chosen()

    @AfterTest
    fun cleanup() {
        project.delete()
        servers.delete()
    }

    @Test
    fun `2,000 callers open, and the selection goes round them quickly`() =
        runComposeUiTest {
            val app = launchWithProject(project, languageServers = servers.settings)
            openFromTree("src/big.php")
            awaitServer(app) { it.isRunning() }
            awaitTag(EditorTags.PROBLEMS, timeout = SERVER_START_MS)
            clickAt(1, FUNCTION_NAME_COLUMN)
            measure("call hierarchy with $CALLERS callers", budgetMillis = 15_000) {
                callHierarchy()
                awaitTag(EditorTags.hierarchyRow(1), timeout = SERVER_START_MS)
                renderFrame()
            }
            // Virtualized: only the rows on screen are composed.
            val composed = onAllNodesWithTag(EditorTags.hierarchyRow(CALLERS)).fetchSemanticsNodes().size
            assertTrue(composed == 0, "the last row should not be composed before it is scrolled to")
            measure("Up from the root to the last of $CALLERS callers", budgetMillis = 3_000) {
                editorKeys { pressKey(Key.DirectionUp) }
                awaitTag(EditorTags.hierarchyRow(CALLERS))
                renderFrame()
            }
            onNodeWithTag(EditorTags.hierarchyRow(CALLERS)).assertIsSelected()
            measure("Left from the last caller back to the root", budgetMillis = 3_000) {
                editorKeys { pressKey(Key.DirectionLeft) }
                awaitTag(EditorTags.hierarchyRow(0))
                renderFrame()
            }
            onNodeWithTag(EditorTags.hierarchyRow(0)).assertIsSelected()
        }
}
