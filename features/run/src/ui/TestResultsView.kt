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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.state.RunSession
import org.example.kotlineditor.state.RunState
import org.example.kotlineditor.state.TestCase
import org.example.kotlineditor.state.TestStatus
import org.example.kotlineditor.state.TestSuite
import org.example.kotlineditor.state.totalsOf

// Row of the tree of results.
private sealed interface TestRow {
    data class Suite(val suite: TestSuite, val expanded: Boolean) : TestRow

    data class Test(val suite: String, val test: TestCase) : TestRow
}

@Composable
internal fun TestsView(run: RunState, session: RunSession) {
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
            Label(
                summary,
                Modifier.testTag(RunTags.SUMMARY),
                color = if (totals.failed > 0) IdeColors.Error else IdeColors.Text,
            )
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
    val chosen = run.selectedTest
    val suite = results.firstOrNull { it.name == chosen?.first }
    return suite?.tests?.firstOrNull { it.name == chosen?.second }
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
                OutputLine(
                    line,
                    RunTags.detailLine(index),
                    { number -> "${RunTags.detailLine(index)}:link:$number" },
                    run::open,
                )
            }
        }
    }
}
