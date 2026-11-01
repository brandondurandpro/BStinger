package org.example.kotlineditor.ui

/**
 * Semantics test tags of the remote module, shared with the end-to-end tests.
 * Paths are relative to the project root.
 */
object RemoteTags {
    const val REMOTE_DIALOG = "remote.dialog"
    const val REMOTE_HOST = "remote.host"
    const val REMOTE_USER = "remote.user"
    const val REMOTE_PORT = "remote.port"
    const val REMOTE_IDENTITY = "remote.identity"
    const val REMOTE_PROJECT = "remote.project"
    const val REMOTE_CONNECT = "remote.connect"
    const val REMOTE_CANCEL = "remote.cancel"
    const val REMOTE_STATUS = "remote.status"
    const val REMOTE_ERROR = "remote.error"
    const val REMOTE_SEARCH = "remote.search"
    const val REMOTE_CATALOG = "remote.catalog"
    const val REMOTE_CONFIG = "remote.config"
    const val REMOTE_HOSTS_LOADING = "remote.hostsLoading"
    const val REMOTE_NEW_HOST = "remote.newHost"
    const val REMOTE_TAGS = "remote.tags"
    const val REMOTE_SELECTED = "remote.selected"
    const val OPEN_PROJECT = "openProject"
    const val OPEN_PROJECT_PATH = "openProject.path"
    const val OPEN_PROJECT_UP = "openProject.up"
    const val OPEN_PROJECT_OPEN = "openProject.open"
    const val OPEN_PROJECT_CANCEL = "openProject.cancel"
    const val OPEN_PROJECT_ERROR = "openProject.error"
    const val OPEN_PROJECT_EMPTY = "openProject.empty"
    const val OPEN_PROJECT_LOADING = "openProject.loading"
    const val OPEN_PROJECT_HOME = "openProject.home"
    const val OPEN_PROJECT_CHECKING = "openProject.checking"
    const val OPEN_PROJECT_COUNT = "openProject.count"

    fun sshHost(alias: String) = "remote.host:$alias"

    fun remoteWorkspace(destination: String, path: String) = "remote.workspace:$destination:$path"

    fun remoteTag(hostKey: String, tag: String) = "remote.tag:$hostKey:$tag"

    /** A directory shown by the Open Project dialog, as an absolute path. */
    fun pickerDirectory(path: String) = "openProject.dir:$path"
}
