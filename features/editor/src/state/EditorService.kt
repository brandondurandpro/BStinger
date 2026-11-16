package org.example.kotlineditor.state

/** The editors of a project (tabs, panes, documents), as [ProjectHost.service] gives them to a feature. */
val EDITOR_SERVICE = ServiceKey<EditorState>("editor")
