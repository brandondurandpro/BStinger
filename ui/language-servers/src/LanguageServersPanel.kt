@file:Suppress("TooManyFunctions")

package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.ServerMemory
import org.example.kotlineditor.state.LanguageServersState
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.memoryText

private val Warning = Color(0xFFE0A458)
private val Good = Color(0xFF73BD79)
private val LEAKS = setOf("SuspectedLeak", "OverLimit")

/** One status bar item per language server of the project, then the IDE server's own memory. */
@Composable
fun LanguageServerStatusItems(project: ProjectState) {
    val state = project.languageServers ?: return
    for (server in state.serversOf(project.root.path)) {
        StatusItem(
            text = statusText(server),
            color = colorOf(server),
            tooltip = tooltipOf(server),
            tag = LanguageServerTags.statusLanguageServer(server.id),
            tooltipTag = LanguageServerTags.statusLanguageServerTooltip(server.id),
        ) { project.showToolWindow(ToolWindow.LanguageServers) }
    }
    val server = state.status.server
    if (server.heapMaxBytes > 0) {
        StatusItem(
            text = "Server ${memoryText(server.heapUsedBytes)}",
            color = IdeColors.TextSecondary,
            tooltip = serverTooltip(server),
            tag = LanguageServerTags.STATUS_SERVER_MEMORY,
            tooltipTag = LanguageServerTags.STATUS_SERVER_MEMORY_TOOLTIP,
        ) { project.showToolWindow(ToolWindow.LanguageServers) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StatusItem(
    text: String,
    color: Color,
    tooltip: String,
    tag: String,
    tooltipTag: String,
    onClick: () -> Unit,
) {
    TooltipArea(tooltip = { MultilineTooltip(tooltip, Modifier.testTag(tooltipTag)) }) {
        Label(
            text,
            Modifier
                .clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand)
                .testTag(tag),
            color = color,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun MultilineTooltip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(IdeColors.Popup, RoundedCornerShape(4.dp))
            .border(1.dp, IdeColors.Separator, RoundedCornerShape(4.dp))
            .padding(8.dp),
    ) {
        Label(text, fontSize = 12.sp, maxLines = 12)
    }
}

/** The Language Servers tool window: every server with its memory and controls, and the IDE server. */
@Composable
fun LanguageServersPanel(project: ProjectState, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val state = project.languageServers
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(LanguageServerTags.LANGUAGE_SERVERS)) {
        ToolWindowHeader("Language Servers", ToolWindow.LanguageServers, project, drag) {
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.LanguageServers, false) },
                Modifier.testTag(LanguageServerTags.LANGUAGE_SERVERS_HIDE),
            ) { CloseIcon() }
        }
        if (state != null) LanguageServersContent(project, state)
    }
}

@Composable
private fun ColumnScope.LanguageServersContent(project: ProjectState, state: LanguageServersState) {
    state.error?.let {
        Label(
            it,
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp).testTag(LanguageServerTags.LANGUAGE_SERVERS_ERROR),
            color = IdeColors.Error,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
    val servers = state.status.servers
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        IdeServerRow(state.status.server)
        if (servers.isEmpty()) {
            Label(
                "No language server is running. Opening a PHP file starts one.",
                Modifier.padding(12.dp).testTag(LanguageServerTags.LANGUAGE_SERVERS_EMPTY),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
            )
        }
        servers.forEach { ServerRow(project, state, it) }
    }
    state.log?.let { log ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        LogView(log.lines, Modifier.weight(1f))
    }
}

@Composable
private fun IdeServerRow(server: ServerMemory) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(LanguageServerTags.LANGUAGE_SERVERS_IDE),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Label("IDE server", fontWeight = FontWeight.Bold)
            Label("Kotlin Editor's own server process", color = IdeColors.TextSecondary, fontSize = 11.sp)
        }
        if (server.heapMaxBytes > 0) {
            Label(serverTooltip(server).replace('\n', ' '), color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
    }
}

@Composable
private fun ServerRow(project: ProjectState, state: LanguageServersState, server: LanguageServerInfo) {
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(LanguageServerTags.languageServerRow(server.id)),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Label("${server.languageName} · ${server.serverName}", fontWeight = FontWeight.Bold)
                Label(
                    "  ${stateText(server)}",
                    Modifier.testTag(LanguageServerTags.languageServerState(server.id)),
                    color = colorOf(server),
                    fontSize = 12.sp,
                )
            }
            Label(server.root, color = IdeColors.TextSecondary, fontSize = 11.sp)
            val growing = server.leakVerdict in LEAKS || server.leakVerdict == "Growing"
            val note = server.message ?: server.leakReason.takeIf { growing }
            note?.let {
                Label(
                    it,
                    Modifier.testTag(LanguageServerTags.languageServerMessage(server.id)),
                    color = colorOf(server),
                    fontSize = 12.sp,
                    maxLines = 3,
                )
            }
        }
        Label(
            memoryDetails(server),
            Modifier.width(260.dp).testTag(LanguageServerTags.languageServerMemory(server.id)),
            color = IdeColors.TextSecondary,
            fontSize = 12.sp,
        )
        ServerActions(project, state, server)
    }
}

@Composable
private fun ServerActions(project: ProjectState, state: LanguageServersState, server: LanguageServerInfo) {
    IdeButton(
        "Restart",
        { state.restart(server) },
        Modifier.testTag(LanguageServerTags.languageServerRestart(server.id)),
    )
    if (state.isRunning(server)) {
        IdeButton(
            "Stop",
            { state.stop(server) },
            Modifier.testTag(LanguageServerTags.languageServerStop(server.id)),
        )
    } else {
        IdeButton(
            "Start",
            { state.start(server) },
            Modifier.testTag(LanguageServerTags.languageServerStart(server.id)),
        )
    }
    IdeButton(
        if (state.log?.id == server.id) "Hide log" else "Log",
        { state.toggleLog(server) },
        Modifier.testTag(LanguageServerTags.languageServerLog(server.id)),
    )
    IdeButton("Change…", { state.offerChoice(server.language, project.root.path) })
}

@Composable
private fun LogView(lines: List<String>, modifier: Modifier) {
    val list = rememberLazyListState()
    LaunchedEffect(lines) { if (lines.isNotEmpty()) list.scrollToItem(lines.lastIndex) }
    LazyColumn(modifier.fillMaxWidth().padding(8.dp).testTag(LanguageServerTags.LANGUAGE_SERVERS_LOG), list) {
        if (lines.isEmpty()) item { Label("The log is empty", color = IdeColors.TextSecondary, fontSize = 12.sp) }
        items(lines) { Label(it, color = IdeColors.Code, fontSize = 12.sp) }
    }
}

private fun stateText(server: LanguageServerInfo): String =
    when (server.state) {
        LanguageServerState.Starting -> "starting…"
        LanguageServerState.Indexing -> "indexing" + (server.progressPercent?.let { " $it %" } ?: "…")
        LanguageServerState.Running -> "running"
        LanguageServerState.Restarting -> "restarting…"
        LanguageServerState.Stopped -> "stopped"
        LanguageServerState.Crashed -> "crashed"
        LanguageServerState.Unavailable -> "unavailable"
    }

private fun statusText(server: LanguageServerInfo): String {
    val leak = if (server.leakVerdict in LEAKS) "⚠ " else ""
    val memory = server.memory?.takeIf { server.state == LanguageServerState.Running }
    val detail = if (memory != null) memoryText(memory.privateBytes) else stateText(server)
    return "$leak${server.languageName} · $detail"
}

private fun colorOf(server: LanguageServerInfo): Color =
    when {
        server.state == LanguageServerState.Crashed -> IdeColors.Error
        server.state == LanguageServerState.Restarting || server.leakVerdict in LEAKS -> Warning
        server.state == LanguageServerState.Unavailable -> IdeColors.Error
        else -> IdeColors.TextSecondary
    }

private fun memoryDetails(server: LanguageServerInfo): String {
    val memory = server.memory ?: return server.pid?.let { "pid $it" } ?: "—"
    val processes = if (memory.processCount > 1) " · ${memory.processCount} processes" else ""
    val peak = memoryText(server.peakPrivateBytes)
    return "${memoryText(memory.privateBytes)} (peak $peak)$processes · pid ${server.pid}"
}

private fun tooltipOf(server: LanguageServerInfo): String =
    buildList {
        add("${server.serverName} for ${server.languageName}: ${stateText(server)}")
        add(server.root)
        server.memory?.let {
            val peak = memoryText(server.peakPrivateBytes)
            add("Private ${memoryText(it.privateBytes)}, resident ${memoryText(it.residentBytes)}, peak $peak")
        }
        if (server.leakReason.isNotEmpty() && server.leakVerdict != "NotEnoughData") add("Memory: ${server.leakReason}")
        server.message?.let(::add)
        add("Click to open the Language Servers window")
    }.joinToString("\n")

// The heap, not the process: in the desktop app the server runs in the UI's process, whose resident memory also
// holds Compose, Skia and (in development) the hot reload agent.
private fun serverTooltip(server: ServerMemory): String {
    val heap = "IDE server: heap ${memoryText(server.heapUsedBytes)} of ${memoryText(server.heapMaxBytes)}"
    val process = "\nWhole process ${memoryText(server.residentBytes)} (the UI too, in the desktop app)"
    return if (server.residentBytes > 0) heap + process else heap
}
