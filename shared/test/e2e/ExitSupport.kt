package org.example.kotlineditor.e2e

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.example.kotlineditor.state.Document
import org.example.kotlineditor.ui.EditorTags
import java.util.concurrent.atomic.AtomicBoolean

/** The files the exit tests edit, then close the IDE with. */
val EDITED_ON_EXIT = listOf("README.md", "docs/guide.md")

/** Opens [path], types "x" in it, and returns its document (it stays modified). */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.edit(app: E2eApp, path: String): Document {
    openFromTree(path)
    onNodeWithTag(EditorTags.EDITOR).performClick()
    typeCode("x")
    awaitTag(EditorTags.tabModified(path))
    val tab = checkNotNull(app.project.editor.activeTab)
    return checkNotNull(tab.document)
}

/** Closes the window as the user does: whether the IDE then closed is told by the flag returned. */
@OptIn(ExperimentalTestApi::class)
fun ComposeUiTest.closeWindow(app: E2eApp): AtomicBoolean {
    val closed = AtomicBoolean(false)
    runOnIdle { app.state.exit { closed.set(true) } }
    return closed
}
