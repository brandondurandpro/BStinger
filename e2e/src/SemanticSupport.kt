package org.example.kotlineditor.e2e

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.text.SpanStyle
import org.example.kotlineditor.ui.EditorTags

/**
 * The style of the character at [column] of the editor line [line], as drawn: the styles covering it merged in
 * order (the language server's over tree-sitter's); null when the line is not shown.
 */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.styleAt(line: Int, column: Int): SpanStyle? {
    val node = onAllNodesWithTag(EditorTags.lineText(line), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()
    val text = node?.config?.getOrNull(SemanticsProperties.Text)?.firstOrNull() ?: return null
    val covering = text.spanStyles.filter { column >= it.start && column < it.end }
    return covering.fold(SpanStyle()) { merged, range -> merged.merge(range.item) }
}
