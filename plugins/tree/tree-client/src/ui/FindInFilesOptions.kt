package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.example.kotlineditor.model.FileEntry
import org.example.kotlineditor.state.TreeHost

/** The options row of the Find in Files popup: the chips, then the glob field and the scope menu when on. */
@Composable
internal fun OptionsSection(project: TreeHost, options: FindChips) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        chip(TreeTags.FIND_IN_FILES_CASE, "Cc", options.matchCase) { options.matchCase = !options.matchCase }
        chip(TreeTags.FIND_IN_FILES_WHOLE_WORD, "W", options.wholeWord) { options.wholeWord = !options.wholeWord }
        chip(TreeTags.FIND_IN_FILES_REGEX, ".*", options.regex) { options.regex = !options.regex }
        chip(TreeTags.FIND_IN_FILES_GLOB_BUTTON, "*", options.globOn) { options.globOn = !options.globOn }
        chip(TreeTags.FIND_IN_FILES_INCLUDE_IGNORED, "◎", options.includeIgnored) {
            options.includeIgnored = !options.includeIgnored
        }
        scopeChip(options.scope) { options.scopeMenuOpen = !options.scopeMenuOpen }
    }
    if (options.globOn) {
        IdeTextField(
            options.globText,
            { options.globText = it },
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .testTag(TreeTags.FIND_IN_FILES_GLOB_INPUT),
            placeholder = "*.kt",
        )
    }
    if (options.scopeMenuOpen) {
        Column(Modifier.fillMaxWidth().testTag(TreeTags.FIND_IN_FILES_SCOPE_MENU)) {
            for (choice in scopeChoices(project)) {
                scopeRow(choice) {
                    options.scope = choice
                    options.scopeMenuOpen = false
                }
            }
        }
    }
}

// An option of the options row: a ToggleChip, tagged like the tests click it.
@Composable
private fun chip(tag: String, text: String, on: Boolean, onToggle: () -> Unit) {
    ToggleChip(text, on, Modifier.testTag(tag), onToggle)
}

// The scope chip: what is searched now (`.` for the whole root), opening the dropdown of the directory choices.
@Composable
private fun scopeChip(scope: String, onOpen: () -> Unit) {
    Box(
        Modifier
            .testTag(TreeTags.FIND_IN_FILES_SCOPE_BUTTON)
            .clickable(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 1.dp),
        contentAlignment = Alignment.Center,
    ) {
        Label(scope, fontSize = 11.sp, color = IdeColors.TextSecondary)
    }
}

// A scope of the dropdown: the root (shown as "the whole project"), or one of the top-level directories.
@Composable
private fun scopeRow(choice: String, onChoose: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onChoose)
            .testTag(TreeTags.findInFilesScopeRow(choice))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Label(if (choice == ".") "the whole project" else choice, fontSize = 12.sp)
    }
}

// The choices of a scope: the root first, then the top-level directories of the project (the index knows them).
private fun scopeChoices(project: TreeHost): List<String> {
    val directories =
        project.fileIndex.files
            .orEmpty()
            .mapNotNull { entry -> topDirectoryOf(project, entry) }
    return listOf(".") + directories.distinct().sortedBy { it.lowercase() }
}

// The directory at the root of the project that contains [entry], null when it is not in one of them.
private fun topDirectoryOf(project: TreeHost, entry: FileEntry): String? {
    val relative = project.fileIndex.relativePath(entry)
    val top = relative.substringBefore('/', missingDelimiterValue = "")
    return top.takeIf { it.isNotEmpty() }
}
