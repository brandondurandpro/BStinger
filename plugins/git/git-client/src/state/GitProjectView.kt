package org.example.kotlineditor.state

/**
 * The project as the Git UI sees it: the [host], plus the states Git keeps in the project, reached through
 * [ProjectHost.service] and not through the host itself. The panels of Git take it where the contributions of
 * the plugin receive a bare [ProjectHost].
 */
class GitProjectView(private val host: ProjectHost) :
    GitHost,
    ProjectHost by host {
    override val git: GitState get() = host.service(GIT_SERVICE)
    override val editor: EditorState get() = host.service(EDITOR_SERVICE)
}
