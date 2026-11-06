package org.example.kotlineditor.e2e

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.test.withKeyDown
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Find in Files runs by the server over a project of [CORPUS_FILES] files of a few kilobytes each. */
@OptIn(ExperimentalTestApi::class)
class FindInFilesPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    private fun ComposeUiTest.openFindInFiles() {
        onNodeWithTag(TreeTags.TREE).performClick()
        onNodeWithTag(TreeTags.TREE).performKeyInput {
            withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.F) } }
        }
        awaitTag(TreeTags.FIND_IN_FILES_INPUT)
    }

    // Ten thousand files across nested directories, each with its one `needle` line: the search hits the limit
    // of the results shown, so the UI receives at most a thousand and the popup stays light whichever the size.
    private fun plantCorpus() {
        val lines = List(CORPUS_LINES - 1) { index -> "filler line $index of a file of some kilobytes" }
        repeat(CORPUS_FILES) { file ->
            val directory = "src/corpus${file / CORPUS_PER_DIRECTORY}/"
            project.file("$directory/file$file.php", (lines + "needle line of file $file").joinToString("\n") + "\n")
        }
    }

    @Test
    fun `a search over ten thousand files runs in time and stops at the limit`() =
        runComposeUiTest {
            plantCorpus()
            val app = launchWithProject(project)
            openFindInFiles()
            measure(
                "search $CORPUS_FILES files, shown limited to 1,000 hits",
                budgetMillis = 15_000,
                budgetMegabytes = 1_500,
            ) {
                val input = onNodeWithTag(TreeTags.FIND_IN_FILES_INPUT)
                input.performTextClearance()
                input.performTextInput("needle")
                // The search runs again while it types: this measure is of the ones that begin at the last key.
                awaitCondition {
                    app.project.findInFiles.finished && app.project.findInFiles.results.size == TEXT_HITS_LIMIT
                }
                renderFrame()
            }
            val results = app.project.findInFiles.results
            assertEquals(TEXT_HITS_LIMIT, results.size, "the popup's limit bounds what the client holds")
            assertEquals(
                TEXT_HITS_LIMIT,
                results.distinctBy { it.entry.path }.size,
                "one hit per file at most: the limit walks the files",
            )
            assertTrue(results.all { it.lineText.contains("needle") }, "every hit is a needle line")
        }

    private companion object {
        const val CORPUS_FILES = 10_000
        const val CORPUS_PER_DIRECTORY = 50
        const val CORPUS_LINES = 40
        const val TEXT_HITS_LIMIT = 1_000
    }
}
