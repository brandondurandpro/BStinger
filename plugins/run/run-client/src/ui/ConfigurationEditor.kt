package org.example.kotlineditor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.RunConfiguration
import org.example.kotlineditor.state.RunKind
import org.example.kotlineditor.state.RunState

@Composable
internal fun ConfigurationEditor(run: RunState, editing: RunConfiguration) {
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
