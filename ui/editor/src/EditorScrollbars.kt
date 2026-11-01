package org.example.kotlineditor.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.v2.ScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.example.kotlineditor.state.EditorTab
import org.example.kotlineditor.state.HorizontalScroll
import org.example.kotlineditor.state.VerticalScroll

@Composable
internal fun BoxScope.EditorScrollbars(tab: EditorTab, metrics: EditorMetrics, viewportWidth: () -> Float) {
    VerticalScrollbar(
        remember(tab) { VerticalScrollAdapter(tab.verticalScroll) },
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().testTag(EditorTags.EDITOR_VERTICAL_SCROLLBAR),
    )
    HorizontalScrollbar(
        remember(
            tab.horizontalScroll,
        ) { HorizontalScrollAdapter(tab.horizontalScroll) { viewportWidth() - metrics.gutterPx } },
        Modifier.align(Alignment.BottomStart).padding(start = metrics.gutterWidth).fillMaxWidth(),
    )
}

private class HorizontalScrollAdapter(private val state: HorizontalScroll, private val viewport: () -> Float) :
    ScrollbarAdapter {
    override val scrollOffset: Double get() = state.offset.toDouble()
    override val contentSize: Double get() = state.maxOffset + viewportSize
    override val viewportSize: Double get() = viewport().toDouble().coerceAtLeast(0.0)

    override suspend fun scrollTo(scrollOffset: Double) = state.scrollTo(scrollOffset.toFloat())
}

class VerticalScrollAdapter(private val state: VerticalScroll) : ScrollbarAdapter {
    override val scrollOffset: Double get() = state.offsetPx.toDouble()
    override val contentSize: Double get() = state.contentHeightPx.toDouble()
    override val viewportSize: Double get() = state.viewportHeightPx.toDouble()

    override suspend fun scrollTo(scrollOffset: Double) = state.scrollToPx(scrollOffset.toFloat())
}
