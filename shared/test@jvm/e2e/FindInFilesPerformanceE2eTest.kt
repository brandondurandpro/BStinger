package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.Tags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

private const val FILES = 10_000
private const val LINES = 40

// Most rows of the result list composed at once: it is virtualized, whatever the number of matches.
private const val MAX_COMPOSED_ROWS = 60

/**
 * Performance of Find in Files on a project of [FILES] files of [LINES] lines (like the reference set of the
 * ripgrep work: about 10 000 files): a query with a few hundred matches, one with more than the limit, and the
 * same queries searched by the built-in engine. Each step prints a `PERF` line and has a budget.
 */
@OptIn(ExperimentalTestApi::class)
class FindInFilesPerformanceE2eTest {
    private val huge =
        SampleProject().apply {
            repeat(FILES) { i ->
                val name = i.toString().padStart(5, '0')
                val lines =
                    List(LINES) { line ->
                        when {
                            line == 0 -> "package demo.p${i % 100}"
                            line == LINES / 2 && i % 25 == 0 -> "    val rareMarker = $i // only in some files"
                            else -> "    fun f${name}_$line(value: Int) = value * $line + commonWord"
                        }
                    }
                file("src/p${i % 100}/File$name.kt", lines.joinToString("\n", postfix = "\n"))
            }
        }

    @AfterTest
    fun cleanup() {
        huge.delete()
    }

    private fun ComposeUiTest.countText(): String =
        onNodeWithTag(Tags.FIND_IN_FILES_COUNT)
            .fetchSemanticsNode()
            .config
            .getOrElseNullable(SemanticsProperties.Text) { null }
            ?.joinToString { it.text }
            .orEmpty()

    @Test
    fun `Find in Files streams the matches of a huge project quickly`() =
        runComposeUiTest {
            launchWithProject(huge)
            openFindInFiles()
            val input = onNodeWithTag(Tags.FIND_IN_FILES_INPUT)
            // Warm-up: the first search starts the engine and fills the disk cache.
            input.performTextInput("warmUp")
            awaitText(Tags.FIND_IN_FILES_COUNT, "No matches", timeout = 60_000)
            input.performTextClearance()
            awaitText(Tags.FIND_IN_FILES_COUNT, "")

            measure("find 400 matches in $FILES files", budgetMillis = 5_000, budgetMegabytes = 400) {
                input.performTextInput("rareMarker")
                awaitText(Tags.FIND_IN_FILES_COUNT, "400 matches in 400 files", timeout = 30_000)
                renderFrame()
            }
            input.performTextClearance()
            awaitText(Tags.FIND_IN_FILES_COUNT, "")

            measure("find more than the limit in $FILES files", budgetMillis = 5_000, budgetMegabytes = 400) {
                input.performTextInput("commonWord")
                // Which files the first 1000 matches are in depends on ripgrep's threads: only the count is known.
                awaitCondition(30_000) { countText().let { it.startsWith("1000+ matches in ") && !it.endsWith("…") } }
                renderFrame()
            }
            val rows = onAllNodes(hasTestTagPrefix("findInFiles.item:")).fetchSemanticsNodes().size
            assertTrue(rows in 1..MAX_COMPOSED_ROWS, "$rows result rows composed, at most $MAX_COMPOSED_ROWS expected")
        }

    @Test
    fun `the built-in search stays usable on a huge project`() =
        runComposeUiTest {
            launchWithProject(huge, server = ServerSetup.withoutRipgrep())
            openFindInFiles()
            val input = onNodeWithTag(Tags.FIND_IN_FILES_INPUT)
            input.performTextInput("warmUp")
            awaitText(Tags.FIND_IN_FILES_COUNT, "No matches", timeout = 60_000)
            input.performTextClearance()
            awaitText(Tags.FIND_IN_FILES_COUNT, "")
            measure("find 400 matches without ripgrep", budgetMillis = 8_000, budgetMegabytes = 1_500) {
                input.performTextInput("rareMarker")
                awaitText(Tags.FIND_IN_FILES_COUNT, "400 matches in 400 files", timeout = 30_000)
                renderFrame()
            }
            awaitTag(Tags.FIND_IN_FILES_ENGINE)
        }
}
