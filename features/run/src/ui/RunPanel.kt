package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.OutputLink
import org.example.kotlineditor.state.RunConfiguration
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunSession
import org.example.kotlineditor.state.RunState
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.RunTab
import org.example.kotlineditor.state.TestCase
import org.example.kotlineditor.state.TestStatus
import org.example.kotlineditor.state.TestSuite
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowLayout
import org.example.kotlineditor.state.findOutputLinks
import org.example.kotlineditor.state.totalsOf

private val ROW_HEIGHT = 20.dp
private val PASSED_COLOR = Color(0xFF5FB86B)
private val SKIPPED_COLOR = Color(0xFF868A91)

/** Run tool window (Alt+4): the run configurations, the output of the command and the tree of test results. */
@Composable
fun RunPanel(run: RunState, layout: ToolWindowLayout, drag: ToolWindowDrag, modifier: Modifier = Modifier) {
    LaunchedEffect(Unit) { run.ensureLoaded() }
    Column(modifier.fillMaxSize().background(IdeColors.Editor).testTag(RunTags.RUN)) {
        ToolWindowHeader(
            "Run",
            ToolWindow.Run,
            layout,
            drag,
            middle = { ConfigurationSelector(run) },
        ) {
            RunActions(run)
            IconButton({ layout.setVisible(ToolWindow.Run, false) }, Modifier.testTag(RunTags.HIDE)) {
                MinimizeIcon()
            }
        }
        val editing = run.editing
        val session = run.session
        when {
            editing != null -> ConfigurationEditor(run, editing)
            session != null -> RunBody(run, session)
            run.configurations.isEmpty() -> EmptyRun(run)
            else -> Hint("Press ▶ to run ${run.selected?.name ?: "a configuration"}")
        }
    }
}

@Composable
private fun ConfigurationSelector(run: RunState) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 8.dp)) {
        Row(
            Modifier
                .background(IdeColors.Hover, RoundedCornerShape(4.dp))
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag(RunTags.SELECTOR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(run.selected?.name ?: "No configuration", modifier = Modifier.padding(end = 4.dp))
            Chevron(expanded = open)
        }
        DropdownMenu(open, { open = false }, Modifier.testTag(RunTags.SELECTOR_MENU)) {
            run.configurations.forEach { configuration ->
                MenuRow(configuration.name, RunTags.configuration(configuration.id)) {
                    run.select(configuration.id)
                    open = false
                }
            }
            MenuRow("New configuration…", RunTags.NEW) {
                run.newConfiguration()
                open = false
            }
            if (run.selected != null) {
                MenuRow("Edit selected…", RunTags.EDIT) {
                    run.editSelected()
                    open = false
                }
            }
        }
    }
}

@Composable
private fun MenuRow(text: String, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(tag),
    ) {
        Label(text)
    }
}

@Composable
private fun RunActions(run: RunState) {
    val session = run.session
    Label(
        session?.let(::statusText) ?: "",
        Modifier.padding(end = 6.dp).testTag(RunTags.STATUS),
        color = session?.let(::statusColor) ?: IdeColors.TextSecondary,
    )
    IconButton({ run.runSelected() }, Modifier.testTag(RunTags.START)) {
        Label("▶", color = if (run.selected != null) PASSED_COLOR else IdeColors.TextSecondary)
    }
    IconButton({ run.stop() }, Modifier.testTag(RunTags.STOP)) {
        Label("■", color = if (session?.running == true) IdeColors.Error else IdeColors.TextSecondary)
    }
}

private fun statusText(session: RunSession): String =
    when (session.status) {
        RunStatus.Running -> "Running…"
        RunStatus.Succeeded -> "Finished (exit code 0)"
        RunStatus.Failed -> "Failed (exit code ${session.exitCode})"
        RunStatus.Stopped -> "Stopped"
        RunStatus.NotStarted -> "Could not start: ${session.error.orEmpty()}"
        RunStatus.Lost -> "Lost: the server restarted"
    }

private fun statusColor(session: RunSession): Color =
    when (session.status) {
        RunStatus.Running -> IdeColors.TextSecondary
        RunStatus.Succeeded -> PASSED_COLOR
        RunStatus.Stopped -> IdeColors.TextSecondary
        RunStatus.Failed, RunStatus.NotStarted, RunStatus.Lost -> IdeColors.Error
    }

@Composable
private fun EmptyRun(run: RunState) {
    Column(
        Modifier.fillMaxSize().padding(16.dp).testTag(RunTags.EMPTY),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Label("No run configuration in this project yet.", color = IdeColors.TextSecondary)
        IdeButton("Add configuration", { run.newConfiguration() }, Modifier.testTag(RunTags.ADD_FIRST))
    }
}

@Composable
private fun Hint(text: String) {
    Box(Modifier.fillMaxSize().padding(16.dp)) { Label(text, color = IdeColors.TextSecondary) }
}

@Composable
private fun RunBody(run: RunState, session: RunSession) {
    val tests = session.configuration.kind == RunKind.Tests
    if (tests) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
            TabChip("Output", run.tab == RunTab.Output, RunTags.TAB_OUTPUT) { run.tab = RunTab.Output }
            TabChip("Tests", run.tab == RunTab.Tests, RunTags.TAB_TESTS) { run.tab = RunTab.Tests }
        }
    }
    if (tests && run.tab == RunTab.Tests) {
        TestsView(run, session)
    } else {
        OutputView(run, session)
    }
}

@Composable
private fun TabChip(text: String, active: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 4.dp)
            .background(if (active) IdeColors.Selection else IdeColors.Panel, RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .testTag(tag),
    ) {
        Label(text, color = if (active) IdeColors.Text else IdeColors.TextSecondary)
    }
}

// The output, one row per line; it follows the end while the user has not scrolled up.
@Composable
private fun OutputView(run: RunState, session: RunSession) {
    val state = rememberLazyListState()
    val count = session.lines.size
    LaunchedEffect(count) {
        val last = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
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
private fun OutputLine(text: String, tag: String, linkTag: (Int) -> String, onOpen: (OutputLink) -> Unit) {
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
            val linkStyle = style.copy(color = IdeColors.Accent, textDecoration = TextDecoration.Underline)
            CodeText(text.substring(link.start, link.end), linkStyle, Modifier.clickable { onOpen(link) }.testTag(linkTag(number)))
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
        text.startsWith("w: ") -> Color(0xFFD9A343)
        else -> IdeColors.Code
    }

// Row of the tree of results.
private sealed interface TestRow {
    data class Suite(val suite: TestSuite, val expanded: Boolean) : TestRow

    data class Test(val suite: String, val test: TestCase) : TestRow
}

@Composable
private fun TestsView(run: RunState, session: RunSession) {
    val results = session.results
    val totals = remember(results) { totalsOf(results) }
    // The suites the user opened or closed; the others are open when something failed in them.
    var toggled by remember(session) { mutableStateOf(emptyMap<String, Boolean>()) }
    Column(Modifier.fillMaxSize().testTag(RunTags.TESTS)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val summary =
                "${totals.passed} passed, ${totals.failed} failed, ${totals.skipped} skipped" +
                    if (totals.running > 0) ", ${totals.running} running" else ""
            Label(summary, Modifier.testTag(RunTags.SUMMARY), color = if (totals.failed > 0) IdeColors.Error else IdeColors.Text)
            TabChip("Failed only", run.onlyFailed, RunTags.ONLY_FAILED) { run.onlyFailed = !run.onlyFailed }
        }
        if (results.isEmpty()) {
            Box(Modifier.testTag(RunTags.NO_TESTS)) { Hint(noTestsText(session)) }
        } else {
            TestTree(run, results, toggled) { toggled = it }
        }
    }
}

@Composable
private fun TestTree(
    run: RunState,
    results: List<TestSuite>,
    toggled: Map<String, Boolean>,
    onToggled: (Map<String, Boolean>) -> Unit,
) {
    val rows = remember(results, toggled, run.onlyFailed) { testRows(results, toggled, run.onlyFailed) }
    Row(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxHeight()) {
            itemsIndexed(rows) { _, row ->
                when (row) {
                    is TestRow.Suite -> {
                        SuiteRow(row) { onToggled(toggled + (row.suite.name to !row.expanded)) }
                    }

                    is TestRow.Test -> {
                        val chosen = row.suite to row.test.name
                        TestRow(row, run.selectedTest == chosen) { run.selectedTest = chosen }
                    }
                }
            }
        }
        val test = selectedCase(run, results)
        if (test != null) {
            VerticalDivider(Modifier.fillMaxHeight(), IdeColors.Separator)
            TestDetails(run, test, Modifier.weight(1f))
        }
    }
}

private fun selectedCase(run: RunState, results: List<TestSuite>): TestCase? {
    val (suiteName, testName) = run.selectedTest ?: return null
    val suite = results.firstOrNull { it.name == suiteName } ?: return null
    return suite.tests.firstOrNull { it.name == testName }
}

private fun noTestsText(session: RunSession): String =
    if (session.running) "Waiting for the first test…" else "The output has no test results"

private fun testRows(results: List<TestSuite>, toggled: Map<String, Boolean>, onlyFailed: Boolean): List<TestRow> {
    val rows = ArrayList<TestRow>()
    for (suite in results) {
        val shown = if (onlyFailed) suite.tests.filter { it.status == TestStatus.Failed } else suite.tests
        if (onlyFailed && shown.isEmpty()) continue
        val expanded = toggled[suite.name] ?: (suite.status == TestStatus.Failed)
        rows += TestRow.Suite(suite, expanded)
        if (expanded) shown.mapTo(rows) { TestRow.Test(suite.name, it) }
    }
    return rows
}

@Composable
private fun SuiteRow(row: TestRow.Suite, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .clickable(onClick = onToggle)
            .padding(horizontal = 8.dp)
            .testTag(RunTags.suite(row.suite.name)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Chevron(expanded = row.expanded)
        StatusMark(row.suite.status)
        Label(row.suite.name, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TestRow(row: TestRow.Test, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(ROW_HEIGHT)
            .background(if (selected) IdeColors.Selection else Color.Transparent)
            .clickable(onClick = onSelect)
            .padding(start = 28.dp, end = 8.dp)
            .testTag(RunTags.test(row.suite, row.test.name)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusMark(row.test.status)
        Label(row.test.name)
    }
}

@Composable
private fun StatusMark(status: TestStatus) {
    val (mark, color) =
        when (status) {
            TestStatus.Passed -> "✓" to PASSED_COLOR
            TestStatus.Failed -> "✗" to IdeColors.Error
            TestStatus.Skipped -> "○" to SKIPPED_COLOR
            TestStatus.Running -> "…" to IdeColors.TextSecondary
        }
    Label(mark, Modifier.width(18.dp), color = color)
}

@Composable
private fun TestDetails(run: RunState, test: TestCase, modifier: Modifier) {
    Column(modifier.fillMaxHeight().padding(8.dp).testTag(RunTags.DETAILS)) {
        Label(test.name, fontWeight = FontWeight.SemiBold)
        if (test.details.isEmpty()) {
            Label("Nothing to report", color = IdeColors.TextSecondary, fontSize = 12.sp)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(test.details) { index, line ->
                OutputLine(line, RunTags.detailLine(index), { number -> "${RunTags.detailLine(index)}:link:$number" }, run::open)
            }
        }
    }
}

@Composable
private fun ConfigurationEditor(run: RunState, editing: RunConfiguration) {
    var name by remember(editing.id) { mutableStateOf(editing.name) }
    var command by remember(editing.id) { mutableStateOf(editing.commandLine) }
    var directory by remember(editing.id) { mutableStateOf(editing.workingDirectory) }
    var kind by remember(editing.id) { mutableStateOf(editing.kind) }
    val exists = run.configurations.any { it.id == editing.id }
    Column(
        Modifier.fillMaxSize().padding(12.dp).testTag(RunTags.EDITOR),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Field("Name", name, { name = it }, RunTags.EDITOR_NAME)
        Field("Command", command, { command = it }, RunTags.EDITOR_COMMAND, "./kotlin test -m core")
        Field("Working directory", directory, { directory = it }, RunTags.EDITOR_DIRECTORY, "the project")
        Row(verticalAlignment = Alignment.CenterVertically) {
            Label("Output", Modifier.width(120.dp), color = IdeColors.TextSecondary)
            TabChip("Program", kind == RunKind.Run, RunTags.EDITOR_KIND_RUN) { kind = RunKind.Run }
            TabChip("Tests", kind == RunKind.Tests, RunTags.EDITOR_KIND_TESTS) { kind = RunKind.Tests }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IdeButton(
                "Save",
                { run.save(RunConfiguration(editing.id, name, kind, command, directory)) },
                Modifier.testTag(RunTags.EDITOR_SAVE),
                primary = true,
                enabled = command.isNotBlank(),
            )
            IdeButton("Cancel", { run.cancelEditing() }, Modifier.testTag(RunTags.EDITOR_CANCEL))
            if (exists) IdeButton("Delete", { run.delete(editing.id) }, Modifier.testTag(RunTags.EDITOR_DELETE))
        }
    }
}

@Composable
private fun Field(label: String, value: String, onChange: (String) -> Unit, tag: String, placeholder: String = "") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Label(label, Modifier.width(120.dp), color = IdeColors.TextSecondary)
        IdeTextField(value, onChange, Modifier.weight(1f).testTag(tag), placeholder = placeholder, monospace = true)
    }
}
