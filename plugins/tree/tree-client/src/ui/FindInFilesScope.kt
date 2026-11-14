package org.example.kotlineditor.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private const val MAX_SCOPE_ROWS = 50
private val MENU_MAX_HEIGHT = 240.dp

// The scope: what is searched now (the whole project, or a directory), opening the dropdown of the directories.
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ScopeChip(options: FindChips) {
    val narrowed = options.scopes.isNotEmpty()
    val tint = if (narrowed) IdeColors.Text else IdeColors.TextSecondary
    TooltipArea(tooltip = { Tooltip("Directory to search") }) {
        Row(
            Modifier
                .testTag(TreeTags.FIND_IN_FILES_SCOPE_BUTTON)
                .background(if (narrowed) IdeColors.Selection else Color.Transparent, IdeShapes.Small)
                .clickable { options.scopeMenuOpen = !options.scopeMenuOpen }
                .padding(horizontal = 5.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            IdeIcon(if (narrowed) Lucide.FolderOpen else Lucide.Folder, color = tint, size = 15.dp)
            Label(
                scopeLabel(options.scopes),
                fontSize = 12.sp,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IdeIcon(Lucide.ChevronDown, color = tint, size = 13.dp)
            if (narrowed) ClearButton(TreeTags.FIND_IN_FILES_SCOPE_CLEAR) { options.scopes = emptyList() }
        }
    }
}

// What the scope chip says: the whole project, the one directory, or how many directories.
private fun scopeLabel(scopes: List<String>): String =
    when (scopes.size) {
        0 -> "Whole project"
        1 -> scopes.single()
        else -> "${scopes.size} directories"
    }

// The dropdown of the directories, left open while several are ticked: a filter field, then the directories
// of the project (all levels) that match it.
@Composable
internal fun ScopeMenu(options: FindChips) {
    val directories = options.facets.directories
    val matching =
        remember(directories, options.scopeFilter) {
            val wanted = options.scopeFilter.trim()
            directories.filter { wanted.isEmpty() || it.contains(wanted, ignoreCase = true) }
        }
    val shown = remember(matching) { listOf(".") + matching.take(MAX_SCOPE_ROWS) }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .background(IdeColors.Input, IdeShapes.Medium)
            .border(1.dp, IdeColors.InputBorder, IdeShapes.Medium)
            .padding(6.dp)
            .testTag(TreeTags.FIND_IN_FILES_SCOPE_MENU),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        IdeTextField(
            options.scopeFilter,
            { options.scopeFilter = it },
            Modifier.fillMaxWidth().testTag(TreeTags.FIND_IN_FILES_SCOPE_FILTER),
            placeholder = "Filter directories",
            leading = { SearchIcon() },
            trailing = {
                if (options.scopeFilter.isNotEmpty()) {
                    ClearButton(TreeTags.FIND_IN_FILES_SCOPE_FILTER_CLEAR) { options.scopeFilter = "" }
                }
            },
        )
        ScopeActions(matching, options)
        Column(Modifier.heightIn(max = MENU_MAX_HEIGHT).verticalScroll(rememberScrollState())) {
            for (choice in shown) {
                val ticked = if (choice == ".") options.scopes.isEmpty() else choice in options.scopes
                ScopeRow(choice, ticked) {
                    options.scopes = toggledScopes(options.scopes, choice)
                }
            }
        }
    }
}

// Ticks every directory the filter leaves (all of them without a filter), or unticks everything.
@Composable
private fun ScopeActions(matching: List<String>, options: FindChips) {
    Row(Modifier.padding(horizontal = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ScopeAction("Select all", TreeTags.FIND_IN_FILES_SCOPE_ALL) {
            options.scopes = (options.scopes + matching).distinct()
        }
        ScopeAction("Clear", TreeTags.FIND_IN_FILES_SCOPE_NONE) { options.scopes = emptyList() }
    }
}

@Composable
private fun ScopeAction(text: String, tag: String, onClick: () -> Unit) {
    Label(text, Modifier.clickable(onClick = onClick).testTag(tag), fontSize = 12.sp, color = IdeColors.TextSecondary)
}

// A directory of the dropdown: its name, then the directory holding it; the root reads as the whole project.
@Composable
private fun ScopeRow(choice: String, current: Boolean, onChoose: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onChoose)
            .testTag(TreeTags.findInFilesScopeRow(choice))
            .resultRow(current)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        IdeIcon(if (choice == ".") Lucide.FolderOpen else Lucide.Folder, size = 14.dp)
        if (choice == ".") {
            Label("Whole project", fontSize = 12.sp, modifier = Modifier.weight(1f))
        } else {
            Label(choice.substringAfterLast('/'), fontSize = 12.sp)
            val parent = choice.substringBeforeLast('/', "")
            val caption = parent.takeIf { it.isNotEmpty() }.orEmpty()
            Label(caption, fontSize = 11.sp, color = IdeColors.TextSecondary, modifier = Modifier.weight(1f))
        }
        if (current) CheckIcon()
    }
}

// The directories after a click on [choice]: the root clears them, a directory is added or taken away.
internal fun toggledScopes(scopes: List<String>, choice: String): List<String> =
    when {
        choice == "." -> emptyList()
        choice in scopes -> scopes - choice
        else -> scopes + choice
    }
