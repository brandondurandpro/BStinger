package org.example.kotlineditor.e2e

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import org.example.kotlineditor.ui.EditorTags
import org.example.kotlineditor.ui.IdeColors

/**
 * The color [word] of the code of row [index] of the list of places is drawn with, read from its semantics: the whole
 * word (`greet` is not the start of `$greeter`); [IdeColors.Code] when it has none, `null` when it is not there.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.previewColorOf(index: Int, word: String): Color? {
    val nodes = onAllNodes(hasTestTag(EditorTags.locationPreview(index)), useUnmergedTree = true)
    val node = nodes.fetchSemanticsNodes().firstOrNull()
    val text = node?.config?.getOrElseNullable(SemanticsProperties.Text) { null }?.firstOrNull()
    val whole = Regex("(?<!\\w)${Regex.escape(word)}(?!\\w)")
    val match = text?.text?.let { whole.find(it) } ?: return null
    val start = match.range.first
    val span = text.spanStyles.lastOrNull { it.start <= start && it.end >= start + word.length }
    return span?.item?.color ?: IdeColors.Code
}

/** Waits until [word] of the code of row [index] of the list of places is drawn in [color]. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.awaitPreviewColor(index: Int, word: String, color: Color, timeout: Long = TIMEOUT) {
    try {
        awaitCondition(timeout) { previewColorOf(index, word) == color }
    } catch (e: ComposeTimeoutException) {
        throw AssertionError("'$word' of row $index is ${previewColorOf(index, word)}, not $color", e)
    }
}
