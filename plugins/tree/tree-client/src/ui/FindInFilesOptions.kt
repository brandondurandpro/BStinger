package org.example.kotlineditor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The toggles of the query, drawn inside its field: match case, whole word, regular expression. */
@Composable
internal fun RowScope.QueryToggles(options: FindChips) {
    IconChip(Lucide.CaseSensitive, options.matchCase, "Match case", Modifier.testTag(TreeTags.FIND_IN_FILES_CASE)) {
        options.matchCase = !options.matchCase
    }
    IconChip(Lucide.WholeWord, options.wholeWord, "Whole word", Modifier.testTag(TreeTags.FIND_IN_FILES_WHOLE_WORD)) {
        options.wholeWord = !options.wholeWord
    }
    IconChip(Lucide.Regex, options.regex, "Regular expression", Modifier.testTag(TreeTags.FIND_IN_FILES_REGEX)) {
        options.regex = !options.regex
    }
}

/** Where and what to search: the directory, the file types and the ignored files, with what each opens below. */
@Composable
internal fun OptionsSection(options: FindChips) {
    Row(
        Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ScopeChip(options)
        val globLabel = if (options.globOn && options.globText.isNotBlank()) options.globText else "File types"
        IconChip(
            Lucide.ListFilter,
            options.globOn,
            "Only some files: *.kt, or an exclusion: !*.xml",
            Modifier.testTag(TreeTags.FIND_IN_FILES_GLOB_BUTTON),
            label = globLabel.take(MAX_GLOB_LABEL),
        ) { options.globOn = !options.globOn }
        IconChip(
            Lucide.Eye,
            options.includeIgnored,
            "Also search the files ignored by Git",
            Modifier.testTag(TreeTags.FIND_IN_FILES_INCLUDE_IGNORED),
            label = "Ignored files",
        ) { options.includeIgnored = !options.includeIgnored }
    }
    if (options.globOn) GlobPanel(options)
    if (options.scopeMenuOpen) ScopeMenu(options)
}

private const val MAX_GLOB_LABEL = 28

// The glob field, then a line of chips for what to keep and one for what to skip: a click adds or removes it.
@Composable
private fun GlobPanel(options: FindChips) {
    val facets = options.facets
    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        IdeTextField(
            options.globText,
            { options.globText = it },
            Modifier.fillMaxWidth().testTag(TreeTags.FIND_IN_FILES_GLOB_INPUT),
            placeholder = "*.kt, *.java, !*.xml, !build/",
            leading = { IdeIcon(Lucide.ListFilter) },
            trailing = {
                if (options.globText.isNotEmpty()) {
                    ClearButton(TreeTags.FIND_IN_FILES_GLOB_CLEAR) { options.globText = "" }
                }
            },
        )
        SuggestionRow("Only", facets.extensions.map { "*.$it" }, options)
        SuggestionRow("Skip", facets.extensions.map { "!*.$it" } + facets.excludable.map { "!$it" }, options)
    }
}

// The cross of a field or of a chip: empties it.
@Composable
internal fun ClearButton(tag: String, onClear: () -> Unit) {
    CloseIcon(Modifier.clickable(onClick = onClear).testTag(tag))
}

// A line of glob chips under its caption, each one lit when the field already has it.
@Composable
private fun SuggestionRow(caption: String, globs: List<String>, options: FindChips) {
    if (globs.isEmpty()) return
    val active = globTokens(options.globText)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Label(caption, Modifier.width(32.dp), fontSize = 11.sp, color = IdeColors.TextSecondary)
        for (glob in globs) {
            ToggleChip(glob, glob in active, Modifier.testTag(TreeTags.findInFilesGlobSuggestion(glob))) {
                options.globText = toggleGlobToken(options.globText, glob)
            }
        }
    }
}
