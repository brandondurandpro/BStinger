package org.example.kotlineditor.state

/** The project as seen by the language server UI: the language servers it runs. */
interface LanguageServersHost : ProjectHost {
    /** Language servers of the IDE; none for the worktrees of agent mode. */
    val languageServers: LanguageServersState?
}
