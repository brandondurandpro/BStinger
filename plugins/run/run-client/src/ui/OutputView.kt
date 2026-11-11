package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.OutputLink
import org.example.kotlineditor.state.RunSession
import org.example.kotlineditor.state.RunState
import org.example.kotlineditor.state.findOutputLinks

// The output, one row per line; it follows the end while the user has not scrolled up.
@Composable
internal fun OutputView(run: RunState, session: RunSession) {
    val state = rememberLazyListState()
    val count = session.lines.size
    LaunchedEffect(count) {
        val lastShown = state.layoutInfo.visibleItemsInfo.lastOrNull()
        val last = lastShown?.index ?: -1
        val following = last >= state.layoutInfo.totalItemsCount - 2
        if (count > 0 && following) state.scrollToItem(count - 1)
    }
    Column(Modifier.fillMaxSize().testTag(RunTags.OUTPUT)) {
        if (session.droppedLines > 0) {
            Label(
                "${session.droppedLines} earlier lines were dropped",
                Modifier.padding(horizontal = 12.dp).testTag(RunTags.DROPPED),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        LazyColumn(Modifier.fillMaxSize(), state) {
            itemsIndexed(session.lines) { index, line ->
                OutputLine(line, RunTags.line(index), { number -> RunTags.link(index, number) }, run::open)
            }
        }
    }
}

// A line of output: plain text, or text cut around the places it names, which open the file.
@Composable
internal fun OutputLine(text: String, tag: String, linkTag: (Int) -> String, onOpen: (OutputLink) -> Unit) {
    val links = remember(text) { findOutputLinks(text) }
    val style = codeStyle(lineColor(text))
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .padding(horizontal = 12.dp)
            .semantics { this.text = AnnotatedString(text) }
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        var from = 0
        links.forEachIndexed { number, link ->
            if (link.start > from) CodeText(text.substring(from, link.start), style)
            val linkStyle = style.copy(color = IdeColors.Link, textDecoration = TextDecoration.Underline)
            CodeText(
                text.substring(link.start, link.end),
                linkStyle,
                Modifier.clickable { onOpen(link) }.testTag(linkTag(number)),
            )
            from = link.end
        }
        if (from < text.length) CodeText(text.substring(from), style)
    }
}

@Composable
private fun CodeText(text: String, style: TextStyle, modifier: Modifier = Modifier) {
    androidx.compose.foundation.text.BasicText(
        text,
        modifier,
        style = style,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
    )
}

@Composable
private fun codeStyle(color: Color): TextStyle = LocalCodeStyle.current.copy(color = color, fontSize = 12.sp)

private fun lineColor(text: String): Color =
    when {
        text.startsWith("e: ") || text.startsWith("ERROR") || text.startsWith("FAILED") -> IdeColors.Error
        text.startsWith("w: ") -> WARNING_COLOR
        else -> IdeColors.Code
    }
