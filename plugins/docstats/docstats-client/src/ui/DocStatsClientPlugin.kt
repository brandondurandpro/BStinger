package org.example.kotlineditor.docstats

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.plugin.ClientContributions
import org.example.kotlineditor.plugin.ClientPlugin
import org.example.kotlineditor.plugin.StatusIndicatorContribution
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.EDITOR_SERVICE
import org.example.kotlineditor.state.ProjectHost
import org.example.kotlineditor.ui.IdeColors
import org.example.kotlineditor.ui.Label

/**
 * The word count of the active document, in the status bar. The count is computed while composing: the document's
 * lines are a snapshot list, so Compose recomputes it when the text changes and keeps the value otherwise.
 */
class DocStatsClientPlugin : ClientPlugin {
    override fun register(contributions: ClientContributions) {
        contributions.statusIndicator(wordCountIndicator())
    }
}

private fun wordCountIndicator() =
    StatusIndicatorContribution(
        key = "WordCount",
        label = "Word count",
    ) { project ->
        val document = project.activeDocument()
        if (document != null) {
            val words = wordCount(document)
            Label(
                "$words words",
                Modifier.testTag(DocStatsTags.ITEM),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
    }

// The active document, like the built-in indicators: null with no tab open, or while a tab loads.
private fun ProjectHost.activeDocument(): Document? = service(EDITOR_SERVICE).activeTab?.document

/**
 * Counts the words separated by whitespace, in one pass over the lines. `Character.isWhitespace` rather than a
 * regex `\s` split: no locale assumption, and no per-line allocations on a document of many lines.
 */
private fun wordCount(document: Document): Int {
    var words = 0
    var inWord = false
    for (line in document.lines) {
        for (char in line) {
            val isSpace = Character.isWhitespace(char)
            if (isSpace) {
                inWord = false
            } else if (!inWord) {
                words++
                inWord = true
            }
        }
        // A line never continues a word of the previous one: only the separator between them does.
        inWord = false
    }
    return words
}
