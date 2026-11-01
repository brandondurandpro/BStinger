package org.example.kotlineditor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.state.FindState
import org.example.kotlineditor.state.replaceAll

/** Find (Ctrl+F) and Replace (Ctrl+R) bar above the editor. */
@Composable
fun FindBar(find: FindState, document: Document, focus: FocusRequester, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(IdeColors.Panel).testTag(EditorTags.FIND_BAR)) {
        FindRow(find, document, focus, onClose)
        if (find.replaceVisible) ReplaceRow(find, document, onClose)
    }
}

@Composable
private fun FindRow(find: FindState, document: Document, focus: FocusRequester, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdeTextField(
            find.query,
            { find.query = it },
            Modifier
                .width(360.dp)
                .testTag(EditorTags.FIND_BAR_INPUT)
                .focusRequester(focus)
                .findKeys(
                    onEnter = { previous -> if (previous) find.previous(document) else find.next(document) },
                    onClose = onClose,
                ),
            placeholder = "Find",
            leading = { SearchIcon() },
            trailing = { ToggleChip("Cc", find.matchCase) { find.matchCase = !find.matchCase } },
        )
        val notFound = find.query.isNotEmpty() && find.matches.isEmpty()
        Label(
            matchCount(find),
            Modifier.padding(horizontal = 10.dp).testTag(EditorTags.FIND_BAR_COUNT),
            color = if (notFound) IdeColors.Error else IdeColors.TextSecondary,
        )
        IconButton({ find.previous(document) }, Modifier.testTag(EditorTags.FIND_BAR_PREVIOUS)) { ArrowIcon(up = true) }
        IconButton({ find.next(document) }, Modifier.testTag(EditorTags.FIND_BAR_NEXT)) { ArrowIcon(up = false) }
        Box(Modifier.weight(1f))
        IconButton(onClose, Modifier.testTag(EditorTags.FIND_BAR_CLOSE)) { CloseIcon() }
    }
}

@Composable
private fun ReplaceRow(find: FindState, document: Document, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IdeTextField(
            find.replacement,
            { find.replacement = it },
            Modifier
                .width(360.dp)
                .testTag(EditorTags.REPLACE_INPUT)
                .findKeys(onEnter = { find.replaceCurrent(document) }, onClose = onClose),
            placeholder = "Replace",
        )
        IdeButton(
            "Replace",
            { find.replaceCurrent(document) },
            Modifier.padding(start = 10.dp).testTag(EditorTags.REPLACE_ONE),
        )
        IdeButton(
            "Replace All",
            { find.replaceAll(document) },
            Modifier.padding(start = 6.dp).testTag(EditorTags.REPLACE_ALL),
        )
    }
}

private fun matchCount(find: FindState): String =
    when {
        find.query.isEmpty() -> ""
        find.matches.isEmpty() -> "0 results"
        else -> "${find.current + 1}/${find.matches.size}"
    }

// Enter (Shift+Enter: backwards) and Escape in the find and replace fields.
private fun Modifier.findKeys(onEnter: (backwards: Boolean) -> Unit, onClose: () -> Unit): Modifier =
    onPreviewKeyEvent { event ->
        val down = event.type == KeyEventType.KeyDown
        val enter = down && (event.key == Key.Enter || event.key == Key.NumPadEnter)
        val escape = down && event.key == Key.Escape
        when {
            enter -> onEnter(event.isShiftPressed)
            escape -> onClose()
            else -> Unit
        }
        enter || escape
    }
