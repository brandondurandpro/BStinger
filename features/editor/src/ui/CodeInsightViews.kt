package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.Diagnostic
import org.example.kotlineditor.model.DiagnosticSeverity
import org.example.kotlineditor.state.CodeInsightState
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.DocumentInsight
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.TextPos
import org.example.kotlineditor.state.visualColumn
import kotlin.math.roundToInt

private val POPUP_SHAPE = RoundedCornerShape(4.dp)
private val HOVER_MAX_WIDTH = 620.dp
private val HOVER_MAX_HEIGHT = 420.dp
private val LIST_WIDTH = 640.dp
private val ROW_HEIGHT = 22.dp
private const val LIST_ROWS = 12
private val LABEL_STYLE = TextStyle(color = IdeColors.Text, fontSize = 13.sp)
private val SECONDARY_STYLE = TextStyle(color = IdeColors.TextSecondary, fontSize = 12.sp)

/** Where an overlay is placed relative to its anchor's line. */
private enum class Placement { Below, Above }

/**
 * A box placed at [anchor] of [document], under its line (or above with [placement] Above, or when it does not fit
 * below): read in the layout phase, so that scrolling moves it without recomposing.
 */
@Composable
private fun Anchored(
    document: Document,
    anchor: TextPos,
    tab: EditorTab,
    metrics: EditorMetrics,
    viewport: () -> IntSize,
    placement: Placement = Placement.Below,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var size by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .offset {
                val line = document.lines.getOrElse(anchor.line) { "" }
                val column = visualColumn(line, anchor.column.coerceAtMost(line.length))
                val textLeft = metrics.gutterPx + metrics.paddingPx - tab.horizontalScroll.offset
                val area = viewport()
                val x = (textLeft + column * metrics.charWidth).coerceAtMost((area.width - size.width).toFloat()).coerceAtLeast(0f)
                val rowTop = document.folding.rowOf(anchor.line) * metrics.lineHeightPx - tab.verticalScroll.offsetPx
                val below = rowTop + metrics.lineHeightPx
                val above = rowTop - size.height
                val fitsBelow = below + size.height <= area.height
                val fitsAbove = above >= 0
                val goesAbove = (placement == Placement.Above && fitsAbove) || (!fitsBelow && fitsAbove)
                IntOffset(x.roundToInt(), (if (goesAbove) above else below).roundToInt())
            }.onSizeChanged { size = it },
    ) { content() }
}

private fun Modifier.popupFrame() =
    background(IdeColors.Popup, POPUP_SHAPE)
        .border(1.dp, IdeColors.Separator, POPUP_SHAPE)

// Clicks on an overlay stay on it: they must not move the caret of the editor below.
private fun Modifier.swallowClicks() = pointerInput(Unit) { detectTapGestures { } }

/** Everything code intelligence shows over [document]: documentation, lists, parameter info, rename, messages. */
@Composable
internal fun CodeInsightOverlays(
    insight: CodeInsightState,
    tab: EditorTab,
    document: Document,
    metrics: EditorMetrics,
    editorFocus: FocusRequester,
    viewport: () -> IntSize,
) {
    insight.signature?.takeIf { it.document === document }?.let { popup ->
        Anchored(document, popup.anchor, tab, metrics, viewport, Placement.Above) { SignatureView(popup.help) }
    }
    insight.hover?.takeIf { it.document === document }?.let { popup ->
        Anchored(document, popup.anchor, tab, metrics, viewport) {
            HoverView(popup.markdown, popup.problems, insight)
        }
    }
    insight.locations?.takeIf { it.document === document }?.let { popup ->
        Anchored(document, popup.anchor, tab, metrics, viewport) { LocationsView(popup, insight) }
    }
    insight.codeActions?.takeIf { it.document === document }?.let { popup ->
        Anchored(document, popup.anchor, tab, metrics, viewport) { CodeActionsView(popup, insight) }
    }
    insight.rename?.takeIf { it.document === document }?.let { popup ->
        val anchor = TextPos(popup.range.startLine, popup.range.startColumn)
        Anchored(document, anchor, tab, metrics, viewport) { RenameView(popup, insight, editorFocus) }
    }
    insight.notice?.takeIf { it.document === document }?.let { notice ->
        Anchored(document, notice.anchor, tab, metrics, viewport, Placement.Above) {
            Box(
                Modifier
                    .background(IdeColors.Popup, POPUP_SHAPE)
                    .border(1.dp, IdeColors.ProblemWarning.copy(alpha = 0.6f), POPUP_SHAPE)
                    .padding(horizontal = 10.dp, vertical = 5.dp)
                    .testTag(EditorTags.NOTICE),
            ) { BasicText(notice.text, style = LABEL_STYLE) }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun HoverView(markdown: String?, problems: List<Diagnostic>, insight: CodeInsightState) {
    Column(
        Modifier
            .widthIn(max = HOVER_MAX_WIDTH)
            .heightIn(max = HOVER_MAX_HEIGHT)
            .popupFrame()
            .onPointerEvent(PointerEventType.Enter) { insight.hoverPopupHovered(true) }
            .onPointerEvent(PointerEventType.Exit) { insight.hoverPopupHovered(false) }
            .swallowClicks()
            .verticalScroll(rememberScrollState())
            .testTag(EditorTags.HOVER),
    ) {
        if (problems.isNotEmpty()) {
            Column(
                Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(EditorTags.HOVER_PROBLEMS),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (problem in problems) ProblemLine(problem)
            }
            if (markdown != null) Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        }
        if (markdown != null) {
            MarkdownText(markdown, Modifier.padding(horizontal = 12.dp, vertical = 8.dp).testTag(EditorTags.HOVER_DOCUMENTATION))
        }
    }
}

@Composable
private fun ProblemLine(problem: Diagnostic) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 4.dp).size(8.dp).background(problemColor(problem.severity), RoundedCornerShape(4.dp)))
        val source = listOfNotNull(problem.source, problem.code).joinToString(" ")
        val text =
            buildAnnotatedString {
                append(problem.message)
                if (source.isNotEmpty()) withStyle(SpanStyle(color = IdeColors.TextSecondary)) { append("  $source") }
            }
        BasicText(text, Modifier.padding(start = 8.dp), style = LABEL_STYLE)
    }
}

@Composable
private fun LocationsView(popup: org.example.kotlineditor.state.LocationsPopup, insight: CodeInsightState) {
    val listState = rememberLazyListState()
    LaunchedEffect(popup.selected) {
        val first = listState.firstVisibleItemIndex
        if (popup.selected < first || popup.selected >= first + LIST_ROWS) listState.scrollToItem(popup.selected)
    }
    Column(Modifier.width(LIST_WIDTH).popupFrame().swallowClicks().testTag(EditorTags.LOCATIONS)) {
        BasicText(
            popup.title,
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp).testTag(EditorTags.LOCATIONS_TITLE),
            style = LABEL_STYLE.copy(fontWeight = FontWeight.Bold),
        )
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = ROW_HEIGHT * LIST_ROWS), state = listState) {
            itemsIndexed(popup.locations) { index, location ->
                val selected = index == popup.selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(ROW_HEIGHT)
                        .background(if (selected) IdeColors.Selection else IdeColors.Popup)
                        .pointerInput(index) { detectTapGestures { insight.openLocation(index) } }
                        .semantics { this.selected = selected }
                        .testTag(EditorTags.location(index))
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val name = popup.names.getOrElse(index) { location.path }
                    BasicText(
                        "${name.substringAfterLast('/')}:${location.range.startLine + 1}",
                        Modifier.width(200.dp),
                        style = LABEL_STYLE,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(
                        location.preview?.trim().orEmpty(),
                        Modifier.weight(1f),
                        style = LocalCodeStyle.current.copy(color = IdeColors.Code, fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    BasicText(name.substringBeforeLast('/', ""), Modifier.padding(start = 8.dp), style = SECONDARY_STYLE, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun CodeActionsView(popup: org.example.kotlineditor.state.CodeActionsPopup, insight: CodeInsightState) {
    Column(Modifier.widthIn(min = 260.dp, max = LIST_WIDTH).popupFrame().swallowClicks().testTag(EditorTags.CODE_ACTIONS)) {
        popup.actions.forEachIndexed { index, action ->
            val selected = index == popup.selected
            Row(
                Modifier
                    .height(ROW_HEIGHT)
                    .background(if (selected) IdeColors.Selection else IdeColors.Popup)
                    .pointerInput(index) { detectTapGestures { insight.applyCodeAction(index) } }
                    .semantics { this.selected = selected }
                    .testTag(EditorTags.codeAction(action.title))
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isFix = action.kind?.startsWith("quickfix") == true
                Box(Modifier.size(8.dp).background(if (isFix) IdeColors.ProblemWarning else IdeColors.Accent, RoundedCornerShape(4.dp)))
                BasicText(action.title, Modifier.padding(start = 8.dp), style = LABEL_STYLE, maxLines = 1)
            }
        }
    }
}

@Composable
private fun SignatureView(help: org.example.kotlineditor.model.SignatureHelpResult) {
    val signature = help.signatures.getOrNull(help.activeSignature) ?: help.signatures.firstOrNull() ?: return
    val parameter = signature.parameters.getOrNull(help.activeParameter)
    val label =
        buildAnnotatedString {
            append(signature.label)
            if (parameter != null) {
                addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = IdeColors.Text), parameter.start, parameter.end)
            }
        }
    Column(Modifier.widthIn(max = HOVER_MAX_WIDTH).popupFrame().swallowClicks().testTag(EditorTags.SIGNATURE).padding(horizontal = 10.dp, vertical = 5.dp)) {
        BasicText(label, style = LocalCodeStyle.current.copy(color = IdeColors.TextSecondary, fontSize = 12.5.sp))
        if (parameter != null) {
            val text = signature.label.substring(parameter.start, parameter.end)
            Box(Modifier.size(0.dp).semantics { this.text = AnnotatedString(text) }.testTag(EditorTags.SIGNATURE_ACTIVE_PARAMETER))
        }
        val documentation = parameter?.documentation?.takeIf { it != signature.label.substring(parameter.start, parameter.end) } ?: signature.documentation
        documentation?.let { MarkdownText(it, Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun RenameView(popup: org.example.kotlineditor.state.RenamePopup, insight: CodeInsightState, editorFocus: FocusRequester) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(popup) { runCatching { focus.requestFocus() } }
    val close = { action: () -> Unit ->
        action()
        runCatching { editorFocus.requestFocus() }
        Unit
    }
    Column(Modifier.width(320.dp).popupFrame().swallowClicks().testTag(EditorTags.RENAME).padding(8.dp)) {
        BasicText("Rename '${popup.oldName}' and its usages to:", style = SECONDARY_STYLE)
        BasicTextField(
            popup.name,
            { popup.name = it },
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .background(IdeColors.Input, RoundedCornerShape(3.dp))
                .border(1.dp, IdeColors.Accent, RoundedCornerShape(3.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when {
                        down && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                            close(insight::confirmRename)
                            true
                        }

                        down && event.key == Key.Escape -> {
                            close(insight::cancelRename)
                            true
                        }

                        else -> false
                    }
                }.testTag(EditorTags.RENAME_INPUT),
            enabled = !popup.applying,
            singleLine = true,
            textStyle = LocalCodeStyle.current.copy(color = IdeColors.Text),
            cursorBrush = SolidColor(IdeColors.Caret),
        )
        BasicText("Enter to rename, Escape to cancel", Modifier.padding(top = 4.dp), style = SECONDARY_STYLE.copy(fontSize = 11.sp))
    }
}

/** The problems of the document at the top right, like IntelliJ's: a click goes to the next one (F2). */
@Composable
internal fun ProblemsWidget(insight: DocumentInsight, onNext: () -> Unit, modifier: Modifier = Modifier) {
    val errors = insight.errorCount
    val warnings = insight.warningCount
    Row(
        modifier
            .padding(6.dp)
            .background(IdeColors.Editor.copy(alpha = 0.85f), POPUP_SHAPE)
            .pointerInput(Unit) { detectTapGestures { onNext() } }
            .padding(horizontal = 6.dp, vertical = 2.dp)
            .testTag(EditorTags.PROBLEMS),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (errors == 0 && warnings == 0) {
            BasicText("✓", style = LABEL_STYLE.copy(color = IdeColors.GitAdded))
        }
        if (errors > 0) Counter("●", errors, DiagnosticSeverity.Error, EditorTags.PROBLEMS_ERRORS)
        if (warnings > 0) Counter("▲", warnings, DiagnosticSeverity.Warning, EditorTags.PROBLEMS_WARNINGS)
    }
}

@Composable
private fun Counter(symbol: String, count: Int, severity: DiagnosticSeverity, tag: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        BasicText(symbol, style = LABEL_STYLE.copy(color = problemColor(severity), fontSize = 11.sp))
        BasicText(count.toString(), Modifier.padding(start = 3.dp).testTag(tag), style = LABEL_STYLE.copy(fontSize = 12.sp))
    }
}
