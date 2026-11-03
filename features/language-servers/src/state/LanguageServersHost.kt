package org.example.kotlineditor.state

/** The project as seen by the language server UI. */
interface LanguageServersHost : ProjectHost {
    /** Language servers of the IDE; none for the worktrees of agent mode. */
    val languageServers: LanguageServersState?

    /** How the inlay hints of the language servers are shown in the editors. */
    var inlayHintsMode: InlayHintsMode
}
