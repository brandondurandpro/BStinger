package org.example.kotlineditor.state

/** The language servers of a project, as [ProjectHost.service] gives them to the UI (none in the worktrees). */
val LANGUAGE_SERVERS_SERVICE = ServiceKey<LanguageServersState>("language-servers")

/**
 * The project as the language server UI sees it: the [host], plus the language servers it runs, reached through
 * [ProjectHost.service] and not through the host itself.
 */
class LanguageServersProjectView(private val host: ProjectHost) :
    LanguageServersHost,
    ProjectHost by host {
    override val languageServers: LanguageServersState? get() = host.serviceOrNull(LANGUAGE_SERVERS_SERVICE)
}
