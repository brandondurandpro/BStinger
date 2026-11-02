package org.example.kotlineditor.state

enum class PopupKind { GotoFile, RecentFiles, FindInFiles, GotoLine, FileStructure, GotoSymbol }

sealed interface Screen {
    data object Welcome : Screen

    class Project(val project: ProjectState) : Screen
}
