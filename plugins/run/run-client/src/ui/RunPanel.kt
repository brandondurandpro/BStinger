package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.semantics.text
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunSession
import org.example.kotlineditor.state.RunState
import org.example.kotlineditor.state.RunStatus
import org.example.kotlineditor.state.RunTab
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.ToolWindowLayout

internal val ROW_HEIGHT = 20.dp
internal val PASSED_COLOR get() = IdeColors.GitAdded
internal val SKIPPED_COLOR get() = IdeColors.TextSecondary
internal val WARNING_COLOR get() = IdeColors.ProblemWarning

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
        if (editing != null) {
            ConfigurationEditor(run, editing)
        } else if (session != null) {
            SessionTabs(run, session)
            RunBody(run, session)
        } else if (run.available.isEmpty()) {
            EmptyRun(run)
        } else {
            Hint("Press ▶ to run ${run.selected?.name ?: "a configuration"}")
        }
    }
}

@Composable
private fun ConfigurationSelector(run: RunState) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(end = 8.dp)) {
        Row(
            Modifier
                .background(IdeColors.Hover, IdeShapes.Medium)
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .testTag(RunTags.SELECTOR),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Label(run.selected?.name ?: "No configuration", modifier = Modifier.padding(end = 4.dp))
            Chevron(expanded = open)
        }
        IdeDropdownMenu(open, { open = false }, Modifier.testTag(RunTags.SELECTOR_MENU)) {
            run.available.forEach { configuration ->
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
        PlayIcon(color = if (run.selected != null) PASSED_COLOR else IdeColors.TextSecondary)
    }
    IconButton({ run.stop() }, Modifier.testTag(RunTags.STOP)) {
        StopIcon(color = if (session?.running == true) IdeColors.Error else IdeColors.TextSecondary)
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
internal fun Hint(text: String) {
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
internal fun TabChip(text: String, active: Boolean, tag: String, onClick: () -> Unit) {
    Box(
        Modifier
            .padding(end = 4.dp)
            .background(if (active) IdeColors.Selection else IdeColors.Panel, IdeShapes.Medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .testTag(tag),
    ) {
        Label(text, color = if (active) IdeColors.Text else IdeColors.TextSecondary)
    }
}
