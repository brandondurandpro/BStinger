package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.example.kotlineditor.docstats.DocStatsTags
import org.example.kotlineditor.ui.AppTags
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse

/** The word count indicator of the status bar, contributed by the docstats plugin. */
@OptIn(ExperimentalTestApi::class)
class DocStatsE2eTest {
    private val project = SampleProject.standard()

    @AfterTest
    fun cleanup() {
        project.delete()
    }

    /** The count the indicator is expected to show: the same whitespace-separated word count as the plugin's. */
    private fun expectedWords(content: String): Int {
        var words = 0
        var inWord = false
        for (char in content) {
            val isSpace = Character.isWhitespace(char)
            if (isSpace) {
                inWord = false
            } else if (!inWord) {
                words++
                inWord = true
            }
        }
        return words
    }

    private fun ComposeUiTest.toggle(indicator: String) {
        // The menu stays open after a choice: it is opened only when it is not.
        val item = AppTags.statusIndicator(indicator)
        val open = onAllNodesWithTag(item).fetchSemanticsNodes().isNotEmpty()
        if (!open) awaitTag(AppTags.STATUS_INDICATORS).performClick()
        awaitTag(item).performClick()
    }

    @Test
    fun `the indicator shows the word count of the opened file`() =
        runComposeUiTest {
            launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            val content = SampleProject.APP_KT
            awaitTag(DocStatsTags.ITEM).assertTextEquals("${expectedWords(content)} words")
        }

    @Test
    fun `a hidden indicator disappears and comes back`() =
        runComposeUiTest {
            val app = launchWithProject(project)
            openFromTree("src/main/kotlin/App.kt")
            awaitTag(DocStatsTags.ITEM)
            toggle("WordCount")
            awaitNoTag(DocStatsTags.ITEM)
            assertFalse(app.project.statusIndicators.isShown("WordCount"))
            awaitTag(AppTags.statusIndicator("WordCount")).performClick()
            awaitTag(DocStatsTags.ITEM)
        }
}
