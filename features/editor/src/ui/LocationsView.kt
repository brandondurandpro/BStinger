package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter
import org.example.kotlineditor.syntax.treesitter.LineSpans

// The three columns of a row: the file and line, the code (the rest), the directory; the same widths on every row.
private val FILE_COLUMN = 180.dp
private val DIRECTORY_COLUMN = 150.dp

/** The list of places (usages, declarations to choose from) under the word it was asked on. */
@Composable
internal fun LocationsView(popup: org.example.kotlineditor.state.LocationsPopup, insight: CodeInsightState) {
    val listState = rememberLazyListState()
    LaunchedEffect(popup.selected) {
        val first = listState.firstVisibleItemIndex
        if (popup.selected < first || popup.selected >= first + LIST_ROWS) listState.scrollToItem(popup.selected)
    }
    Column(
        Modifier
            .width(LIST_WIDTH)
            .popupFrame()
            .swallowClicks()
            .testTag(EditorTags.LOCATIONS),
    ) {
        BasicText(
            popup.title,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp).testTag(EditorTags.LOCATIONS_TITLE),
            style = POPUP_LABEL_STYLE.copy(fontWeight = FontWeight.Bold),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = LIST_ROW_HEIGHT * LIST_ROWS), state = listState) {
            itemsIndexed(popup.locations) { index, location -> LocationRow(popup, index, location, insight) }
        }
    }
}

// A place of the list: its file and line, its code colored like in the editor, its directory.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LocationRow(
    popup: org.example.kotlineditor.state.LocationsPopup,
    index: Int,
    location: org.example.kotlineditor.model.SourceLocation,
    insight: CodeInsightState,
) {
    val background =
        when (index) {
            popup.selected -> IdeColors.Selection
            popup.hovered -> IdeColors.Hover
            else -> IdeColors.Popup
        }
    val name = popup.names.getOrElse(index) { location.path }
    val preview = popup.previews.getOrElse(index) { "" }
    val treeSitterColors = popup.previewColors?.getOrNull(index)
    val code = remember(preview, treeSitterColors) { coloredPreview(preview, name, treeSitterColors) }
    Row(
        Modifier
            .fillMaxWidth()
            .height(LIST_ROW_HEIGHT)
            .background(background)
            .onPointerEvent(PointerEventType.Enter) { popup.hover(index) }
            .onPointerEvent(PointerEventType.Exit) { popup.unhover(index) }
            .pointerInput(index) { detectTapGestures { insight.openLocation(index) } }
            .semantics { this.selected = index == popup.selected }
            .testTag(EditorTags.location(index))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(
            "${name.substringAfterLast('/')}:${location.range.startLine + 1}",
            Modifier.width(FILE_COLUMN).testTag(EditorTags.locationFile(index)),
            style = POPUP_LABEL_STYLE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(
            code,
            Modifier.weight(1f).testTag(EditorTags.locationPreview(index)),
            style = LocalCodeStyle.current.copy(color = IdeColors.Code, fontSize = 12.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        BasicText(
            name.substringBeforeLast('/', ""),
            Modifier.padding(start = 8.dp).width(DIRECTORY_COLUMN).testTag(EditorTags.locationDirectory(index)),
            style = POPUP_SECONDARY_STYLE,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// The language the line lexer colors the file [fileName] as: PHP, which it does not know, is C-like for it (as in the
// code blocks of the documentation).
private fun lexedLanguageOf(fileName: String): Language {
    val language = Language.fromFileName(fileName)
    val isPhp = fileName.endsWith(".php", ignoreCase = true)
    return if (language == Language.Plain && isPhp) Language.CLike else language
}

// [code] with the colors of the tree-sitter grammar of the file [fileName], or of its line lexer until they are known.
private fun coloredPreview(code: String, fileName: String, treeSitterColors: LineSpans?): AnnotatedString {
    val spans = treeSitterColors ?: SyntaxHighlighter.highlight(code, lexedLanguageOf(fileName), false)
    return buildAnnotatedString {
        append(code)
        for (i in 0 until spans.size) {
            val end = minOf(spans.ends[i], code.length)
            if (spans.starts[i] < end) addStyle(syntaxStyle(spans.styles[i]), spans.starts[i], end)
        }
    }
}
