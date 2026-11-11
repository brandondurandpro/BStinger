package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.RunSession
import org.example.kotlineditor.state.RunState

// One tab per run, from the first one.
@Composable
internal fun SessionTabs(run: RunState, shown: RunSession) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp)) {
        for (candidate in run.sessions.toList()) {
            SessionChip(run, candidate, active = candidate === shown)
        }
    }
}

// The name of a run (a dot while it runs) and a cross to close it.
@Composable
private fun SessionChip(run: RunState, candidate: RunSession, active: Boolean) {
    val id = candidate.configuration.id
    val name = candidate.configuration.name
    val label = if (candidate.running) "● $name" else name
    val background = if (active) IdeColors.Selection else IdeColors.Panel
    val color = if (active) IdeColors.Text else IdeColors.TextSecondary
    Row(
        Modifier
            .padding(end = 4.dp)
            .background(background, IdeShapes.Medium)
            .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(label, Modifier.clickable { run.show(candidate) }.testTag(RunTags.session(id)), color = color)
        Label(
            "×",
            Modifier
                .clickable { run.close(candidate) }
                .padding(horizontal = 6.dp)
                .testTag(RunTags.sessionClose(id)),
            color = IdeColors.TextSecondary,
        )
    }
}
