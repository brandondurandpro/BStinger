package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import org.example.kotlineditor.state.PopupKind
import org.example.kotlineditor.state.delete
import org.example.kotlineditor.ui.TreeTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val DIRECTORIES = 250
private const val FILES_PER_DIRECTORY = 20
private const val CORPUS_FILES = DIRECTORIES * FILES_PER_DIRECTORY

// The files SampleProject.standard() has (its `.git` directory is not listed by the search).
private const val SAMPLE_FILES = 7
private const val LIMIT = 50

/**
 * The file lists behind Go to File: loading the index of a corpus of [CORPUS_FILES] small files across
 * [DIRECTORIES] directories, then a fuzzy query over the whole index and the display of its [LIMIT] results.
 * Each step prints a `PERF` line and has a budget several times what a developer machine measures.
 */
@OptIn(ExperimentalTestApi::class)
class SearchFilesPerformanceE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    // 5 000 one-line files, 20 in each of 250 directories, quick to build and cheap to search for a prefix.
    private fun build() {
        for (directory in 0 until DIRECTORIES) {
            for (index in 0 until FILES_PER_DIRECTORY) {
                val number = directory * FILES_PER_DIRECTORY + index
                project.file("src/corpus/corpus$directory/Q$number.kt", "package demo\n")
            }
        }
    }

    @Test
    fun `the file index loads and Go to File queries over five thousand files`() =
        runComposeUiTest {
            build()
            val app = launchWithProject(project)

            // Opening the popup starts the index: the whole corpus listed through the server is the first step.
            runOnUiThread { app.project.showPopup(PopupKind.GotoFile) }
            awaitTag(TreeTags.GOTO_FILE_INPUT)
            measure("load the file index of some ${CORPUS_FILES} files", budgetMillis = 15_000, budgetMegabytes = 800) {
                awaitCondition { app.project.fileIndex.files != null }
                renderFrame()
            }
            // Nothing but the corpus is in the index: the count is exact (the index of it is built once).
            val indexed = app.project.fileIndex.files
            assertEquals(CORPUS_FILES + SAMPLE_FILES, indexed?.size)

            // Go to File's query itself, over the whole index: once warm, then the same query measured whole.
            val prefix = "q49"
            runBlocking { app.project.fileIndex.searchFiles(prefix, limit = LIMIT) }
            measure(
                "Go to File fuzzy query over the ${CORPUS_FILES} files",
                budgetMillis = 1_000,
                budgetMegabytes = 200,
            ) {
                val matches = runBlocking { app.project.fileIndex.searchFiles(prefix, limit = LIMIT) }
                assertEquals(LIMIT, matches.size)
                // Of the corpus alone (no file of the sample project is named like this), best first.
                assertTrue(matches.all { it.entry.name.startsWith("Q") && it.relativePath.startsWith("src/corpus/") })
            }

            // Displayed in the popup, that list composes no row beyond its limit.
            onNodeWithTag(TreeTags.GOTO_FILE_INPUT).performTextInput(prefix)
            awaitCondition { onAllNodes(hasTestTagPrefix("gotoFile.item")).fetchSemanticsNodes().isNotEmpty() }
            renderFrame()
            val rows = onAllNodes(hasTestTagPrefix("gotoFile.item")).fetchSemanticsNodes().size
            assertTrue(rows <= LIMIT, "composed rows: $rows")
        }
}
