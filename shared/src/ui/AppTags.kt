package org.example.kotlineditor.ui

/**
 * Semantics test tags of the application shell (screens, tool windows, shortcuts, agents), shared with the
 * end-to-end tests. Paths are relative to the project root.
 */
@Suppress("TooManyFunctions")
object AppTags {
    const val WELCOME = "welcome"
    const val WELCOME_OPEN = "welcome.open"
    const val WELCOME_SEARCH = "welcome.search"
    const val WELCOME_ERROR = "welcome.error"
    const val WELCOME_REMOTE = "welcome.remote"
    const val REMOTE_DISCONNECT = "remote.disconnect"
    const val TELEMETRY_BANNER = "telemetry.banner"
    const val TELEMETRY_ACCEPT = "telemetry.accept"
    const val TELEMETRY_DECLINE = "telemetry.decline"
    const val TELEMETRY_DETAILS = "telemetry.details"
    const val TELEMETRY_SENT = "telemetry.sent"
    const val PROJECT_TELEMETRY = "project.telemetry"
    const val STATUS_REMOTE = "status.remote"
    const val TOP_REMOTE = "toolbar.remote"
    const val PROJECT = "project"
    const val PROJECT_WIDGET = "project.widget"
    const val PROJECT_CLOSE = "project.close"
    const val PROJECT_AGENT_MODE = "project.agentMode"
    const val PROJECT_OPEN_OTHER = "project.openOther"
    const val PROJECT_MENU = "project.menu"
    const val PROJECT_SETTINGS = "project.settings"
    const val WELCOME_SETTINGS = "welcome.settings"
    const val SETTINGS = "settings"
    const val SETTINGS_CLOSE = "settings.close"
    const val SETTINGS_SEARCH = "settings.search"
    const val SETTINGS_TITLE = "settings.title"
    const val SETTINGS_EMPTY = "settings.empty"
    const val SETTINGS_FONT_SIZE = "settings.fontSize"
    const val SETTINGS_FONT_SMALLER = "settings.fontSize.smaller"
    const val SETTINGS_FONT_LARGER = "settings.fontSize.larger"
    const val SETTINGS_FONT_RESET = "settings.fontSize.reset"
    const val SETTINGS_SERVERS_LOADING = "settings.servers.loading"
    const val SETTINGS_SERVERS_ERROR = "settings.servers.error"
    const val SETTINGS_TELEMETRY_FORBIDDEN = "settings.telemetry.forbidden"
    const val SETTINGS_AGENT_COMMAND = "settings.agentCommand"
    const val TOOL_PROJECT = "tool.project"
    const val TOOL_GIT = "tool.git"
    const val TOOL_BRANCHES = "tool.branches"
    const val TOOL_TERMINAL = "tool.terminal"
    const val TOOL_NETWORK = "tool.network"
    const val NETWORK = "network"
    const val NETWORK_RECORD = "network.record"
    const val NETWORK_CLEAR = "network.clear"
    const val NETWORK_PINGS = "network.pings"
    const val NETWORK_HIDE = "network.hide"
    const val NETWORK_FILTER = "network.filter"
    const val NETWORK_TOTALS = "network.totals"
    const val NETWORK_EMPTY = "network.empty"
    const val NETWORK_UNAVAILABLE = "network.unavailable"
    const val NETWORK_DETAIL = "network.detail"
    const val WORKSPACE = "workspace"
    const val MODE_SWITCH = "mode.switch"
    const val MODE_IDE = "mode.ide"
    const val MODE_AGENT = "mode.agent"
    const val AGENT = "agent"
    const val AGENT_LIST = "agent.list"
    const val AGENT_NEW = "agent.new"
    const val AGENT_FILTER = "agent.filter"
    const val AGENT_DELETE = "agent.delete"
    const val AGENT_TABS = "agent.tabs"
    const val AGENT_EMPTY = "agent.empty"
    const val AGENT_PROMPT = "agent.prompt"
    const val AGENT_SEND = "agent.send"
    const val AGENT_STOP = "agent.stop"
    const val AGENT_THINKING = "agent.thinking"
    const val AGENT_NAME = "agent.name"
    const val AGENT_TERMINAL = "agent.terminal"
    const val AGENT_STRIP_CONVERSATIONS = "agent.strip.conversations"
    const val AGENT_DETAILS = "agent.details"
    const val AGENT_DETAIL_CONVERSATION = "agent.detail.conversation"
    const val AGENT_DETAIL_EDITOR = "agent.detail.editor"
    const val AGENT_WORKSPACE_STATUS = "agent.workspaceStatus"
    const val AGENT_MODE_DIRECT = "agent.mode.direct"
    const val AGENT_MODE_WORKTREE = "agent.mode.worktree"
    const val FILE_DIALOG = "fileDialog"
    const val FILE_DIALOG_INPUT = "fileDialog.input"
    const val FILE_DIALOG_OK = "fileDialog.ok"
    const val FILE_DIALOG_ERROR = "fileDialog.error"
    const val EDITOR_EMPTY = "editor.empty"
    const val EDITOR_LOADING = "editor.loading"
    const val STATUS_BAR = "status"
    const val STATUS_CARET = "status.caret"
    const val STATUS_LANGUAGE = "status.language"
    const val STATUS_PATH = "status.path"
    const val STATUS_CONNECTION = "status.connection"
    const val STATUS_OPENING = "status.opening"
    const val STATUS_ERROR = "status.error"
    const val TOOL_LANGUAGE_SERVERS = "tool.languageServers"
    const val GOTO_LINE = "gotoLine"
    const val GOTO_LINE_INPUT = "gotoLine.input"

    fun pane(index: Int) = "editor.pane:$index"

    fun dockSplitter(dock: String) = "dock.splitter:$dock"

    fun agentConversation(id: Int) = "agent.conversation.$id"

    fun agentTab(id: Int) = "agent.tab.$id"

    fun agentTabClose(id: Int) = "agent.tab.$id.close"

    fun agentTabDetails(id: Int) = "agent.tab.$id.details"

    fun agentView(id: Int) = "agent.view.$id"

    fun agentMessage(index: Int) = "agent.message.$index"

    /** A button of the pending permission request, by the id of its option (`allow`). */
    fun agentPermission(option: String) = "agent.permission:$option"

    fun recentProject(path: String) = "recent:$path"

    fun recentRemove(path: String) = "recent.remove:$path"

    /** A recent project in the menu of the toolbar's project name, which switches to it. */
    fun switchProject(path: String) = "project.switch:$path"

    /** A row of the empty editor's hint list, named after its action. */
    fun editorHint(action: String) = "editor.hint:$action"

    /** The shortcut shown next to an empty editor's hint. */
    fun editorHintShortcut(action: String) = "editor.hint.shortcut:$action"

    fun networkRecord(seq: Long) = "network.record:$seq"

    /** A category of the Settings dialog's list, by its enum name (`Editor`). */
    fun settingsCategory(name: String) = "settings.category:$name"

    /** A setting of the Settings dialog, by its enum name (`FontSize`). */
    fun setting(name: String) = "settings.setting:$name"

    /** The check box of an on/off setting, by its enum name (`AutoSelect`). */
    fun settingToggle(name: String) = "settings.toggle:$name"

    /** The button of an inlay hints mode (`Inline`) in the Settings dialog. */
    fun settingsInlayHints(mode: String) = "settings.inlayHints:$mode"

    /** A language server offered in the Settings dialog, by its id (`phpactor`). */
    fun settingsServer(id: String) = "settings.server:$id"

    /** The button that switches to a language server, by its id. */
    fun settingsServerUse(id: String) = "settings.server.use:$id"
}
