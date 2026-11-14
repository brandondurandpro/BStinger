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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
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
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.LanguageServerInfo
import org.example.kotlineditor.model.LanguageServerState
import org.example.kotlineditor.model.ProjectCheckResult
import org.example.kotlineditor.state.InlayHintsMode
import org.example.kotlineditor.state.LanguageServersHost
import org.example.kotlineditor.state.LanguageServersState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.memoryText

private val Warning get() = IdeColors.GitBehind
private val Good get() = IdeColors.GitAdded
private val LEAKS = setOf("SuspectedLeak", "OverLimit")

/**
 * One status bar item per language server of the project, then the memory and the processor use of the IDE: each
 * group only when the user keeps it shown.
 */
@Composable
fun LanguageServerStatusItems(
    project: LanguageServersHost,
    showServers: Boolean = true,
    showMemory: Boolean = true,
    showCpu: Boolean = true,
) {
    val state = project.languageServers ?: return
    val servers = if (showServers) state.serversOf(project.root.path) else emptyList()
    for (server in servers) {
        StatusItem(
            text = statusText(server),
            color = colorOf(server),
            dot = dotColor(server),
            tooltip = tooltipOf(server),
            tag = LanguageServerTags.statusLanguageServer(server.id),
            tooltipTag = LanguageServerTags.statusLanguageServerTooltip(server.id),
        ) { project.showToolWindow(ToolWindow.LanguageServers) }
    }
    if (showMemory) IdeMemoryStatusItems(project, state)
    if (showCpu) IdeCpuStatusItem(project, state)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun StatusItem(
    text: String,
    color: Color,
    tooltip: String,
    tag: String,
    tooltipTag: String,
    dot: Color? = null,
    onClick: () -> Unit,
) {
    TooltipArea(tooltip = { MultilineTooltip(tooltip, Modifier.testTag(tooltipTag)) }) {
        Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
            dot?.let { StatusDot(it) }
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
}

@Composable
private fun MultilineTooltip(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(IdeColors.Popup, IdeShapes.Medium)
            .border(1.dp, IdeColors.Separator, IdeShapes.Medium)
            .padding(8.dp),
    ) {
        Label(text, fontSize = 12.sp, maxLines = 12)
    }
}

/** The Language Servers tool window: every server with its memory and controls, and the IDE server. */
@Composable
fun LanguageServersPanel(project: LanguageServersHost, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    val state = project.languageServers
    Column(modifier.fillMaxSize().background(IdeColors.Panel).testTag(LanguageServerTags.LANGUAGE_SERVERS)) {
        ToolWindowHeader("Language Servers", ToolWindow.LanguageServers, project.toolWindows, drag) {
            IconButton(
                { project.toolWindows.setVisible(ToolWindow.LanguageServers, false) },
                Modifier.testTag(LanguageServerTags.LANGUAGE_SERVERS_HIDE),
            ) { CloseIcon() }
        }
        if (state != null) LanguageServersContent(project, state)
    }
}

@Composable
private fun ColumnScope.LanguageServersContent(project: LanguageServersHost, state: LanguageServersState) {
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
    // One column, read from top to bottom: the few settings, the servers they apply to, then the IDE's own use.
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
        Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH).fillMaxWidth()) {
            SettingsSection(project, state, servers)
            SectionDivider()
            ServersSection(project, state, servers)
            SectionDivider()
            IdeMemorySection(state)
        }
    }
    state.log?.let { log ->
        Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
        LogView(log.lines, Modifier.weight(1f))
    }
}

// The column stops growing here, so that a window as wide as the screen does not stretch its cards.
private val CONTENT_MAX_WIDTH = 860.dp

// One line for the inlay hints mode and the check of the project, the notes and the check's result under it.
@Composable
private fun SettingsSection(
    project: LanguageServersHost,
    state: LanguageServersState,
    servers: List<LanguageServerInfo>,
) {
    Column(SECTION, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            InlayHintsModeRow(project, Modifier.weight(1f))
            ProjectCheckButton(project, state)
        }
        InlayHintsNote(servers)
        SetupNote(servers)
        ProjectCheckResult(project, state)
    }
}

// A card per running language server, or why there is none.
@Composable
private fun ServersSection(
    project: LanguageServersHost,
    state: LanguageServersState,
    servers: List<LanguageServerInfo>,
) {
    Column(SECTION, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Active servers")
        if (servers.isEmpty()) {
            Label(
                "No language server is running. Opening a PHP, TypeScript or JavaScript file starts one.",
                Modifier.testTag(LanguageServerTags.LANGUAGE_SERVERS_EMPTY),
                color = IdeColors.TextSecondary,
                fontSize = 12.sp,
                maxLines = 3,
            )
        }
        servers.forEach { ServerRow(project, state, it) }
    }
}

// Padding of a section of the window, between two dividers.
internal val SECTION = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)

@Composable
private fun SectionDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(IdeColors.Separator))
}

// The check of the whole project with vue-tsc: the language servers only know the files that are open.
@Composable
private fun ProjectCheckButton(project: LanguageServersHost, state: LanguageServersState) {
    IdeButton(
        if (state.checking) "Checking…" else "Check project (vue-tsc)",
        { state.checkProject(project.root.path) },
        Modifier.testTag(LanguageServerTags.PROJECT_CHECK),
        enabled = !state.checking,
        icon = Lucide.Check,
    )
}

// What the last check found: its summary, then one line per problem.
@Composable
private fun ProjectCheckResult(project: LanguageServersHost, state: LanguageServersState) {
    state.projectCheck?.let {
        Label(
            it.summary,
            Modifier.testTag(LanguageServerTags.PROJECT_CHECK_SUMMARY),
            color = if (it.status == FeatureStatus.Ready) IdeColors.TextSecondary else IdeColors.Error,
            fontSize = 12.sp,
            maxLines = 2,
        )
    }
    val lines = checkLines(project, state.projectCheck)
    if (lines.isNotEmpty()) {
        LazyColumn(
            Modifier
                .fillMaxWidth()
                .height(PROBLEMS_HEIGHT)
                .testTag(LanguageServerTags.PROJECT_CHECK_PROBLEMS),
        ) {
            items(lines) { Label(it, fontSize = 12.sp, maxLines = 1) }
        }
    }
}

// One line per problem: `src/App.vue:12:5  message`.
private fun checkLines(project: LanguageServersHost, check: ProjectCheckResult?): List<String> =
    check?.files.orEmpty().flatMap { file ->
        val inside = file.path.removePrefix(project.root.path).trimStart('/', '\\')
        val relative = inside.replace('\\', '/')
        file.diagnostics.map { problem ->
            val where = "$relative:${problem.range.startLine + 1}:${problem.range.startColumn + 1}"
            "$where  ${problem.message.lineSequence().firstOrNull().orEmpty()}"
        }
    }

private val PROBLEMS_HEIGHT = 160.dp

// How the inlay hints (`name:` before an argument) show in the editors: not at all, above the caret's line, or in
// the code.
@Composable
private fun InlayHintsModeRow(project: LanguageServersHost, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Label("Inlay hints", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        SegmentedControl(
            InlayHintsMode.entries,
            project.inlayHintsMode,
            { project.inlayHintsMode = it },
            labelOf = { it.name },
            tagOf = { LanguageServerTags.inlayHintsMode(it.name) },
        )
    }
}

// Why the running servers that give no inlay hints give none (intelephense without its licence): the mode chosen
// above would otherwise seem to do nothing.
@Composable
private fun InlayHintsNote(servers: List<LanguageServerInfo>) {
    val notes = servers.mapNotNull { it.inlayHintsNote }.distinct()
    if (notes.isEmpty()) return
    Label(
        notes.joinToString("\n"),
        Modifier.testTag(LanguageServerTags.INLAY_HINTS_NOTE),
        color = IdeColors.TextSecondary,
        fontSize = 12.sp,
        maxLines = 8,
    )
}

// A dependency the project lacks, which costs features of a running server (Vue without `vue` installed).
@Composable
private fun SetupNote(servers: List<LanguageServerInfo>) {
    val notes = servers.mapNotNull { it.setupNote }.distinct()
    if (notes.isEmpty()) return
    Label(
        notes.joinToString("\n"),
        Modifier.testTag(LanguageServerTags.SETUP_NOTE),
        color = IdeColors.Error,
        fontSize = 12.sp,
        maxLines = 8,
    )
}

// A muted name on the left, its value on the right in the code font.
@Composable
internal fun MemoryLine(name: String, value: String, valueTag: String? = null) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Label(name, Modifier.weight(1f), color = IdeColors.TextSecondary, fontSize = 12.sp)
        MonoValue(value, if (valueTag != null) Modifier.testTag(valueTag) else Modifier)
    }
}

@Composable
internal fun MonoValue(value: String, modifier: Modifier = Modifier) {
    val style = LocalCodeStyle.current.copy(color = IdeColors.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    BasicText(value, modifier, style = style, maxLines = 1)
}

// A card per server: its state as a dot, its memory, then its actions.
@Composable
private fun ServerRow(project: LanguageServersHost, state: LanguageServersState, server: LanguageServerInfo) {
    Column(
        Modifier
            .fillMaxWidth()
            .card()
            .padding(12.dp)
            .testTag(LanguageServerTags.languageServerRow(server.id)),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(dotColor(server))
            Label(
                "${server.languageName} · ${server.serverName}",
                Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
            )
            server.memory?.let { MonoValue(memoryText(it.privateBytes)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Label(
                stateText(server),
                Modifier.testTag(LanguageServerTags.languageServerState(server.id)),
                color = colorOf(server),
                fontSize = 12.sp,
            )
            Label(
                memoryDetails(server),
                Modifier.testTag(LanguageServerTags.languageServerMemory(server.id)),
                color = IdeColors.TextSecondary,
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
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ServerActions(project, state, server)
        }
    }
}

@Composable
private fun ServerActions(project: LanguageServersHost, state: LanguageServersState, server: LanguageServerInfo) {
    IdeButton(
        "Restart",
        { state.restart(server) },
        Modifier.testTag(LanguageServerTags.languageServerRestart(server.id)),
        compact = true,
    )
    if (state.isRunning(server)) {
        IdeButton(
            "Stop",
            { state.stop(server) },
            Modifier.testTag(LanguageServerTags.languageServerStop(server.id)),
            compact = true,
        )
    } else {
        IdeButton(
            "Start",
            { state.start(server) },
            Modifier.testTag(LanguageServerTags.languageServerStart(server.id)),
            compact = true,
        )
    }
    IdeButton(
        if (state.log?.id == server.id) "Hide log" else "Log",
        { state.toggleLog(server) },
        Modifier.testTag(LanguageServerTags.languageServerLog(server.id)),
        variant = ButtonVariant.Ghost,
        compact = true,
    )
    IdeButton(
        "Change…",
        { state.offerChoice(server.language, project.root.path) },
        variant = ButtonVariant.Ghost,
        compact = true,
    )
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

// Green while it runs, amber while it gets ready, red when it failed, muted when stopped.
private fun dotColor(server: LanguageServerInfo): Color =
    when (server.state) {
        LanguageServerState.Running -> Good
        LanguageServerState.Starting, LanguageServerState.Indexing, LanguageServerState.Restarting -> Warning
        LanguageServerState.Crashed, LanguageServerState.Unavailable -> IdeColors.Error
        LanguageServerState.Stopped -> IdeColors.TextSecondary
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
