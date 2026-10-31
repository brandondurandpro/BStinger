package org.example.kotlineditor.ui

import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import org.example.kotlineditor.state.AppState
import org.example.kotlineditor.state.PopupKind
import org.example.kotlineditor.state.ProjectState
import org.example.kotlineditor.state.ToolWindow
import org.example.kotlineditor.state.gotoDeclaration
import org.example.kotlineditor.state.navigateBack
import org.example.kotlineditor.state.saveAll
import org.example.kotlineditor.state.selectNext
import org.example.kotlineditor.state.selectPrevious
import org.example.kotlineditor.state.selectedText

private const val DOUBLE_SHIFT_MILLIS = 400L

/** What IDE-wide shortcuts act on. */
internal class IdeContext(val app: AppState, val project: ProjectState, val findFocus: FocusRequester) {
    val editor get() = project.editor

    /** Focus target of the active pane's editor. */
    val editorFocus: FocusRequester get() = editor.activePane.focusRequester

    fun openFindBar(replace: Boolean = false) {
        val document = editor.activeTab?.document ?: return
        document.selectedText().takeIf { it.isNotEmpty() && '\n' !in it }?.let { project.find.query = it }
        project.find.visible = true
        project.find.replaceVisible = replace
        runCatching { findFocus.requestFocus() }
    }

    fun closeFindBar() {
        project.find.visible = false
        project.find.replaceVisible = false
        runCatching { editorFocus.requestFocus() }
    }

    fun revealActiveFile() {
        editor.activeTab?.let {
            project.toolWindows.setVisible(ToolWindow.Project, true)
            project.tree.reveal(it.entry.path)
        }
    }
}

private fun bind(
    keys: Set<Key>,
    ctrl: Boolean? = false,
    shift: Boolean? = null,
    alt: Boolean? = false,
    action: IdeContext.(Modifiers) -> Unit,
) = binding(keys, ctrl, shift, alt, action)

// IntelliJ default keymap, first match wins. Alt+Shift+. / Alt+Shift+, change the font size (Ctrl+- / Ctrl+= fold).
private val IDE_SHORTCUTS =
    listOf(
        bind(setOf(Key.N), ctrl = true, shift = true, alt = true) { project.showPopup(PopupKind.GotoSymbol) },
        bind(setOf(Key.N), ctrl = true, shift = true) { project.showPopup(PopupKind.GotoFile) },
        bind(setOf(Key.P), ctrl = true) { project.showPopup(PopupKind.GotoFile) },
        bind(setOf(Key.DirectionLeft), ctrl = true, alt = true) { project.navigateBack() },
        bind(setOf(Key.B), ctrl = true) { project.gotoDeclaration() },
        bind(setOf(Key.R), ctrl = true) { openFindBar(replace = true) },
        bind(setOf(Key.F), ctrl = true, shift = true) { project.showPopup(PopupKind.FindInFiles) },
        bind(setOf(Key.F), ctrl = true, shift = false) { openFindBar() },
        bind(setOf(Key.G), ctrl = true) { project.showPopup(PopupKind.GotoLine) },
        bind(setOf(Key.E), ctrl = true) { project.showPopup(PopupKind.RecentFiles) },
        bind(setOf(Key.F12), ctrl = true) { project.showPopup(PopupKind.FileStructure) },
        bind(setOf(Key.Period), shift = true, alt = true) { app.zoomEditor(1f) },
        bind(setOf(Key.Comma), shift = true, alt = true) { app.zoomEditor(-1f) },
        bind(setOf(Key.Zero, Key.NumPad0), shift = true, alt = true) { app.resetEditorZoom() },
        bind(setOf(Key.F1), alt = true) { revealActiveFile() },
        bind(setOf(Key.S), ctrl = true) { editor.saveAll() },
        bind(setOf(Key.F4), ctrl = true) { editor.activeTab?.let(editor::close) },
        bind(setOf(Key.Tab), ctrl = true) { if (it.shift) editor.selectPrevious() else editor.selectNext() },
        bind(setOf(Key.DirectionLeft), alt = true) { editor.selectPrevious() },
        bind(setOf(Key.DirectionRight), alt = true) { editor.selectNext() },
        bind(setOf(Key.One), alt = true) { project.toggleToolWindow(ToolWindow.Project) },
        bind(setOf(Key.Nine), alt = true) { project.toggleToolWindow(ToolWindow.Git) },
        bind(setOf(Key.F12), alt = true) { project.toggleToolWindow(ToolWindow.Terminal) },
        bind(setOf(Key.R), ctrl = true, shift = true, alt = true) { app.toggleEditorRenderer() },
        bind(setOf(Key.D), ctrl = true, shift = true, alt = true) {
            app.devtools = true
            project.toggleToolWindow(ToolWindow.Network)
        },
        bind(setOf(Key.F3)) {
            editor.activeTab?.document?.let { document ->
                if (it.shift) {
                    project.find.previous(document)
                } else {
                    project.find.next(document)
                }
            }
        },
    )

/** IDE-wide shortcuts, intercepted before the focused component sees them. */
internal class GlobalShortcuts(private val context: IdeContext) {
    private val doubleShift = DoubleShift()

    fun handle(event: KeyEvent): Boolean {
        val project = context.project
        // The terminal owns the keyboard: every key is input for the shell, except the one that hides it.
        if (project.terminal.focused && !escapesTerminal(event)) return false
        val closesFindBar = event.key == Key.Escape && project.find.visible && project.popup == null
        return when {
            doubleShift.isDoubleShift(event) -> {
                project.showPopup(PopupKind.GotoFile)
                true
            }

            event.type != KeyEventType.KeyDown -> {
                false
            }

            closesFindBar -> {
                context.closeFindBar()
                true
            }

            else -> {
                IDE_SHORTCUTS.dispatch(event, context)
            }
        }
    }
}

// Keys the terminal does not get: hiding it, and the devtools.
private fun escapesTerminal(event: KeyEvent): Boolean =
    when (event.key) {
        Key.F12 -> {
            true
        }

        Key.D -> {
            val held = Modifiers(event)
            held.ctrl && held.shift && held.alt
        }

        else -> {
            false
        }
    }

/** Detects Shift pressed twice in a row (Search Everywhere / Go to File in IntelliJ). */
private class DoubleShift {
    private var lastRelease = 0L
    private var otherKeySinceShift = false

    fun isDoubleShift(event: KeyEvent): Boolean {
        val isShift = event.key == Key.ShiftLeft || event.key == Key.ShiftRight
        val pressed = event.type == KeyEventType.KeyDown
        if (pressed) otherKeySinceShift = !isShift
        val released = isShift && event.type == KeyEventType.KeyUp && !otherKeySinceShift
        val now = System.currentTimeMillis()
        val double = released && now - lastRelease < DOUBLE_SHIFT_MILLIS
        if (released) lastRelease = if (double) 0 else now
        return double
    }
}
