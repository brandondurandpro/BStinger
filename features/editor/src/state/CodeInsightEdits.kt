package org.example.kotlineditor.state

import kotlinx.coroutines.launch
import org.example.kotlineditor.model.CodeActionsResult
import org.example.kotlineditor.model.EditResult
import org.example.kotlineditor.model.FeatureStatus
import org.example.kotlineditor.model.FormatOptions
import org.example.kotlineditor.model.RenameTarget
import org.example.kotlineditor.model.TextRange

// What code intelligence changes in the code: rename (Shift+F6), reformat (Ctrl+Alt+L), quick fixes (Alt+Enter).

/** Shift+F6: asks what can be renamed at the caret, then shows the rename popup with its name. */
fun CodeInsightState.startRename(path: String, document: Document) {
    dismissHover()
    val caret = document.caret
    scope.launch {
        val version = document.version
        val target = askAt(path, document, caret) { servers, at -> servers.prepareRename(at) }
        if (document.version == version) renameAsked(path, document, caret, target)
    }
}

private fun CodeInsightState.renameAsked(path: String, document: Document, caret: TextPos, target: RenameTarget?) {
    val range = target?.range
    val name = target?.placeholder ?: range?.let { textOf(document, it) }
    val status = target?.status ?: FeatureStatus.NotReady
    when {
        status == FeatureStatus.NotReady -> notify(document, caret, NOT_READY)
        status == FeatureStatus.Unsupported -> notify(document, caret, "The language server cannot rename")
        range == null || name == null -> notify(document, caret, target?.message ?: "Cannot rename this element")
        else -> rename = RenamePopup(document, path, range, name)
    }
}

/** Enter in the rename popup: renames everywhere, in the open documents and the files. */
fun CodeInsightState.confirmRename() {
    val popup = rename ?: return
    val newName = popup.name.trim()
    val unchanged = newName.isEmpty() || newName == popup.oldName
    if (unchanged) {
        rename = null
    } else {
        popup.applying = true
        val anchor = TextPos(popup.range.startLine, popup.range.startColumn)
        scope.launch {
            val result = askAt(popup.path, popup.document, anchor) { servers, at -> servers.rename(at, newName) }
            rename = null
            applyResult(popup.document, anchor, result ?: EditResult.NotReady, "rename")
        }
    }
}

/** Escape in the rename popup. */
fun CodeInsightState.cancelRename() {
    rename = null
}

/** Ctrl+Alt+L: reformats the selection, or the whole document. */
fun CodeInsightState.reformat(path: String, document: Document) {
    val range =
        document.selectionRange()?.let { (start, end) ->
            TextRange(
                start.line,
                start.column,
                end.line,
                end.column,
            )
        }
    val anchor = document.caret
    scope.launch {
        val version = document.version
        val result = askVersion(path, document) { servers, at -> servers.format(path, at, range, FormatOptions()) }
        if (document.version == version) applyResult(document, anchor, result ?: EditResult.NotReady, "reformat")
    }
}

/** Alt+Enter: the quick fixes and refactorings at the caret, or the selection. */
fun CodeInsightState.showCodeActions(path: String, document: Document) {
    dismissHover()
    val caret = document.caret
    val selected =
        document.selectionRange()?.let { (start, end) ->
            TextRange(
                start.line,
                start.column,
                end.line,
                end.column,
            )
        }
    val range = selected ?: TextRange(caret.line, caret.column, caret.line, caret.column)
    scope.launch {
        val version = document.version
        val result = askVersion(path, document) { servers, at -> servers.codeActions(path, at, range) }
        if (document.version == version) actionsAsked(document, caret, result)
    }
}

private fun CodeInsightState.actionsAsked(document: Document, caret: TextPos, result: CodeActionsResult?) {
    val status = result?.status ?: FeatureStatus.NotReady
    val actions = result?.actions.orEmpty()
    when {
        status == FeatureStatus.NotReady -> notify(document, caret, NOT_READY)
        result == null || actions.isEmpty() -> notify(document, caret, "No actions available here")
        else -> codeActions = CodeActionsPopup(document, caret, result.token, actions)
    }
}

/** Applies the quick fix [index] of the list, or the selected one. */
fun CodeInsightState.applyCodeAction(index: Int? = null) {
    val popup = codeActions ?: return
    val chosen = index ?: popup.selected
    if (popup.actions.getOrNull(chosen) != null) {
        codeActions = null
        scope.launch {
            val result = askServers { servers -> servers.applyCodeAction(popup.token, chosen) }
            applyResult(popup.document, popup.anchor, result ?: EditResult.NotReady, "apply the action")
        }
    }
}

/** Closes the quick fixes. */
fun CodeInsightState.dismissCodeActions() {
    codeActions = null
}

internal suspend fun CodeInsightState.applyResult(
    document: Document,
    anchor: TextPos,
    result: EditResult,
    what: String,
) {
    val failure =
        when {
            result.status == FeatureStatus.NotReady -> NOT_READY
            result.status == FeatureStatus.Unsupported -> "The language server cannot $what"
            result.message != null -> result.message
            else -> host.apply(result)
        }
    failure?.let { notify(document, anchor, it) }
}
