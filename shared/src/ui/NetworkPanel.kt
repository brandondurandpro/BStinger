package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.example.kotlineditor.model.Direction
import org.example.kotlineditor.model.NetworkInspector
import org.example.kotlineditor.model.NetworkRecord
import org.example.kotlineditor.model.NetworkTotals
import org.example.kotlineditor.state.NetworkView
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.formatBytes
import org.example.kotlineditor.state.hexDump
import org.example.kotlineditor.state.summary
import org.example.kotlineditor.state.wireStrings
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The list is refreshed at most this often (a StateFlow keeps only the latest totals while we wait): the terminal
// alone can send dozens of frames per second.
private const val REFRESH_MS = 200L
private val NETWORK_ROW_HEIGHT = 22.dp
private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

private class NetworkCapture(val records: List<NetworkRecord>, val totals: NetworkTotals)

/** Network devtools window (Ctrl+Alt+Shift+D): every frame exchanged with the server, captured while it is shown. */
@Composable
internal fun NetworkPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val view = project.network
    val inspector = view.inspector
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(Tags.NETWORK)) {
        ToolWindowHeader("Network", ToolWindow.Network, project, drag) {
            if (inspector != null) CaptureActions(view)
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.Network, false) },
                Modifier.testTag(Tags.NETWORK_HIDE),
            ) { CloseIcon() }
        }
        if (inspector == null) {
            Box(Modifier.fillMaxSize().padding(12.dp).testTag(Tags.NETWORK_UNAVAILABLE)) {
                Label("No server connection to inspect", color = IdeColors.TextSecondary, fontSize = 12.sp)
            }
        } else {
            Recording(view, inspector)
            val capture by rememberCapture(inspector)
            Toolbar(view, capture.totals)
            Row(Modifier.weight(1f).fillMaxWidth()) {
                FrameList(view, view.visible(capture.records).asReversed(), Modifier.weight(1f))
                view.selected?.let { selected ->
                    VerticalDivider(Modifier.fillMaxHeight(), IdeColors.Separator)
                    FrameDetail(selected, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CaptureActions(view: NetworkView) {
    IconButton({ view.paused = !view.paused }, Modifier.testTag(Tags.NETWORK_RECORD)) {
        Label("●", color = if (view.paused) IdeColors.TextSecondary else IdeColors.Error)
    }
    IconButton({ view.hidePings = !view.hidePings }, Modifier.testTag(Tags.NETWORK_PINGS)) {
        Label("♡", color = if (view.hidePings) IdeColors.TextSecondary else IdeColors.Text)
    }
    IconButton(view::clear, Modifier.testTag(Tags.NETWORK_CLEAR)) {
        Label("⊘", color = IdeColors.TextSecondary)
    }
}

// The capture runs while the panel is on screen (closed, moved away or project closed: it stops).
@Composable
private fun Recording(view: NetworkView, inspector: NetworkInspector) {
    LaunchedEffect(inspector, view.paused) { inspector.recording = !view.paused }
    DisposableEffect(inspector) { onDispose { inspector.recording = false } }
}

@Composable
private fun rememberCapture(inspector: NetworkInspector) =
    produceState(NetworkCapture(inspector.snapshot(), inspector.totals.value), inspector) {
        inspector.totals.collect { totals ->
            value = NetworkCapture(inspector.snapshot(), totals)
            delay(REFRESH_MS)
        }
    }

@Composable
private fun Toolbar(view: NetworkView, totals: NetworkTotals) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdeTextField(
            view.filter,
            { view.filter = it },
            Modifier.width(220.dp).testTag(Tags.NETWORK_FILTER),
            placeholder = "Filter (method, kind)",
        )
        Label(
            "${totals.frames} frames · ↑ ${formatBytes(totals.sentBytes)} (wire ${formatBytes(totals.sentWireBytes)})" +
                " · ↓ ${formatBytes(totals.receivedBytes)} (wire ${formatBytes(totals.receivedWireBytes)})" +
                if (view.paused) " · paused" else "",
            Modifier.padding(start = 12.dp).testTag(Tags.NETWORK_TOTALS),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun FrameList(view: NetworkView, records: List<NetworkRecord>, modifier: Modifier) {
    Column(modifier.fillMaxHeight()) {
        FrameRow(
            listOf("Time", "", "Kind", "Method", "Id", "Size", "Wire", "Latency"),
            color = IdeColors.TextSecondary,
            background = IdeColors.Panel,
        )
        if (records.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(12.dp).testTag(Tags.NETWORK_EMPTY)) {
                Label(
                    if (view.paused) "Recording paused" else "Waiting for frames…",
                    color = IdeColors.TextSecondary,
                    fontSize = 12.sp,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(records, key = { it.seq }) { record ->
                    FrameRow(
                        cellsOf(record),
                        color = colorOf(record),
                        background = if (view.selected?.seq == record.seq) IdeColors.Selection else Color.Transparent,
                        modifier =
                        Modifier
                            .clickable { view.selected = record }
                            .testTag(ItemTags.networkRecord(record.seq)),
                    )
                }
            }
        }
    }
}

private val NETWORK_COLUMNS: List<Dp?> = listOf(92.dp, 16.dp, 72.dp, null, 56.dp, 64.dp, 64.dp, 56.dp)

@Composable
private fun FrameRow(cells: List<String>, color: Color, background: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .height(NETWORK_ROW_HEIGHT)
            .background(background)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEachIndexed { index, text ->
            val width = NETWORK_COLUMNS[index]
            Label(
                text,
                if (width == null) Modifier.weight(1f).padding(end = 6.dp) else Modifier.width(width),
                color = color,
                fontSize = 12.sp,
            )
        }
    }
}

private fun cellsOf(record: NetworkRecord): List<String> =
    listOf(
        TIME_FORMAT.format(Instant.ofEpochMilli(record.timeMs)),
        if (record.direction == Direction.Sent) "↑" else "↓",
        record.kind,
        record.method.orEmpty(),
        if (record.id == 0L) "" else "#${record.id}",
        formatBytes(record.size.toLong()),
        formatBytes(record.wireSize.toLong()),
        record.latencyMs?.let { "$it ms" }.orEmpty(),
    )

private fun colorOf(record: NetworkRecord): Color =
    when (record.kind) {
        "Error", "Reject" -> IdeColors.Error
        "Event", "Ping", "Pong" -> IdeColors.TextSecondary
        else -> IdeColors.Text
    }

@Composable
private fun FrameDetail(record: NetworkRecord, modifier: Modifier) {
    val strings = remember(record) { wireStrings(record.body) }
    val hex = remember(record) { hexDump(record.body) }
    val code = LocalCodeStyle.current.copy(fontSize = 12.sp, lineHeight = 16.sp)
    LazyColumn(modifier.fillMaxHeight().padding(8.dp).testTag(Tags.NETWORK_DETAIL)) {
        item {
            val arrow = if (record.direction == Direction.Sent) "↑ Sent" else "↓ Received"
            Label(
                "$arrow ${record.kind} ${record.method.orEmpty()} ${if (record.id == 0L) "" else "#${record.id}"}",
                fontWeight = FontWeight.SemiBold,
            )
            Label(record.summary(), Modifier.padding(vertical = 4.dp), IdeColors.TextSecondary, 12.sp)
        }
        if (strings.isNotEmpty()) {
            item { Section("Strings") }
            items(strings) { BasicText(it, style = code.copy(color = IdeColors.StringLiteral)) }
        }
        if (hex.isNotEmpty()) {
            item { Section("Payload (hex)") }
            items(hex) { BasicText(it, style = code.copy(color = IdeColors.Code), softWrap = false, maxLines = 1) }
        }
    }
}

@Composable
private fun Section(title: String) {
    Label(title, Modifier.padding(top = 8.dp, bottom = 2.dp), IdeColors.TextSecondary, 11.sp, FontWeight.SemiBold)
}
