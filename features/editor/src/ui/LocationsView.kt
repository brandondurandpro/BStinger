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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.SourceLocation
import org.example.kotlineditor.model.TextRange
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.LocationsPopup
import org.example.kotlineditor.syntax.Language
import org.example.kotlineditor.syntax.SyntaxHighlighter
import org.example.kotlineditor.syntax.treesitter.LineSpans

// The three columns of a row: the file and line, the code (the rest), the directory; the same widths on every row,
// fitted to the widest content up to these limits.
private val MIN_POPUP_WIDTH = 420.dp
private val MAX_POPUP_WIDTH = 1000.dp
private val MIN_FILE_COLUMN = 90.dp
private val MAX_FILE_COLUMN = 260.dp
private val MAX_DIRECTORY_COLUMN = 240.dp
private val ROW_PADDING = 20.dp
private val COLUMN_GAP = 16.dp
private val BOLD_SLACK = 16.dp

private data class LocationColumns(val file: Dp, val directory: Dp, val total: Dp)

private fun fileLabel(name: String, location: SourceLocation): String =
    "${name.substringAfterLast('/')}:${location.range.startLine + 1}"

private fun widest(texts: List<String>, style: TextStyle, measurer: TextMeasurer, density: Density): Dp {
    val pixels = texts.maxOfOrNull { measurer.measure(it, style, maxLines = 1).size.width } ?: 0
    return with(density) { pixels.toDp() }
}

// The widths the content asks for, each column and the whole popup kept between their limits.
private fun columnsOf(
    popup: LocationsPopup,
    code: TextStyle,
    measurer: TextMeasurer,
    density: Density,
): LocationColumns {
    val labels =
        popup.locations.mapIndexed { index, location ->
            val name = popup.names.getOrElse(index) { location.path }
            fileLabel(name, location)
        }
    val directories = popup.names.map { it.substringBeforeLast('/', "") }
    val labelsWidth = widest(labels, POPUP_LABEL_STYLE, measurer, density)
    val directoriesWidth = widest(directories, POPUP_SECONDARY_STYLE, measurer, density)
    val fileWidth = labelsWidth.coerceIn(MIN_FILE_COLUMN, MAX_FILE_COLUMN)
    val directoryWidth = directoriesWidth.coerceAtMost(MAX_DIRECTORY_COLUMN)
    val codeWidth = widest(popup.previews, code, measurer, density)
    val total = fileWidth + codeWidth + directoryWidth + COLUMN_GAP + COLUMN_GAP + ROW_PADDING + BOLD_SLACK
    return LocationColumns(fileWidth, directoryWidth, total.coerceIn(MIN_POPUP_WIDTH, MAX_POPUP_WIDTH))
}

/** The list of places (usages, declarations to choose from) under the word it was asked on. */
@Composable
internal fun LocationsView(popup: LocationsPopup, insight: CodeInsightState) {
    val listState = rememberLazyListState()
    LaunchedEffect(popup.selected) {
        val first = listState.firstVisibleItemIndex
        if (popup.selected < first || popup.selected >= first + LIST_ROWS) listState.scrollToItem(popup.selected)
    }
    val codeStyle = LocalCodeStyle.current.copy(color = IdeColors.Code, fontSize = 12.sp)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val columns = remember(popup) { columnsOf(popup, codeStyle, measurer, density) }
    Column(
        Modifier
            .width(columns.total)
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
            itemsIndexed(popup.locations) { index, location -> LocationRow(popup, index, location, columns, insight) }
        }
    }
}

// A place of the list: its file and line, its code colored like in the editor, its directory.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun LocationRow(
    popup: LocationsPopup,
    index: Int,
    location: SourceLocation,
    columns: LocationColumns,
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
    val usage = usageSpan(location.preview, location.range)
    val code = remember(preview, treeSitterColors, usage) { coloredPreview(preview, name, treeSitterColors, usage) }
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
            fileLabel(name, location),
            Modifier.padding(end = COLUMN_GAP).width(columns.file).testTag(EditorTags.locationFile(index)),
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
            Modifier
                .padding(start = COLUMN_GAP)
                .width(columns.directory)
                .testTag(EditorTags.locationDirectory(index)),
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
private fun coloredPreview(
    code: String,
    fileName: String,
    treeSitterColors: LineSpans?,
    usage: IntRange?,
): AnnotatedString {
    val spans = treeSitterColors ?: SyntaxHighlighter.highlight(code, lexedLanguageOf(fileName), false)
    return buildAnnotatedString {
        append(code)
        for (i in 0 until spans.size) {
            val end = minOf(spans.ends[i], code.length)
            if (spans.starts[i] < end) addStyle(syntaxStyle(spans.styles[i]), spans.starts[i], end)
        }
        if (usage != null && usage.first < code.length) {
            addStyle(SpanStyle(fontWeight = FontWeight.Bold), usage.first, minOf(usage.last + 1, code.length))
        }
    }
}

/**
 * Where the usage [range] is in the trimmed [preview] of its line (the preview shown is without its indentation), null
 * when the line is unknown. A usage over several lines ends with the first.
 */
internal fun usageSpan(preview: String?, range: TextRange): IntRange? {
    if (preview == null) return null
    val indentation = preview.length - preview.trimStart().length
    val start = range.startColumn - indentation
    val sameLine = range.endLine == range.startLine
    val lineEnd = preview.trimEnd().length
    val end = (if (sameLine) range.endColumn else lineEnd) - indentation
    val inside = start >= 0 && end > start
    return if (inside) start until end else null
}
