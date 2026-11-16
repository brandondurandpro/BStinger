package org.example.kotlineditor.ui

/** Semantics test tags of agent mode (its switch, conversations, prompt, settings), shared with the end-to-end tests. */
object AgentTags {
    const val SETTINGS_AGENTS = "settings.agents"
    const val MODE_SWITCH = "mode.switch"
    const val MODE_AGENT = "mode.agent"
    const val AGENT = "agent"
    const val AGENT_LIST = "agent.list"
    const val AGENT_NEW = "agent.new"
    const val AGENT_FILTER = "agent.filter"
    const val AGENT_DELETE = "agent.delete"
    const val AGENT_REVIEW = "agent.review"
    const val AGENT_LOGIN = "agent.login"
    const val AGENT_PIN = "agent.pin"
    const val AGENT_DUPLICATE = "agent.duplicate"
    const val AGENT_ARCHIVE = "agent.archive"
    const val AGENT_PROJECT_GROUP = "agent.group.project"
    const val AGENT_ARCHIVED = "agent.archived"
    const val AGENT_USAGE = "agent.usage"
    const val AGENT_QUOTA = "agent.quota"
    const val AGENT_COMMANDS = "agent.commands"
    const val AGENT_COMMANDS_FILTER = "agent.commands.filter"
    const val AGENT_COST = "agent.cost"
    const val AGENT_QUOTA_CARD = "agent.quota.card"
    const val AGENT_CARD_CONTEXT = "agent.quota.card.context"
    const val AGENT_CARD_COST = "agent.quota.card.cost"
    const val AGENT_DETAIL_TABS = "agent.detail.tabs"
    const val AGENT_IMAGE_VIEWER = "agent.image.viewer"
    const val AGENT_IMAGE_CLOSE = "agent.image.close"
    const val AGENT_STATS = "agent.stats"
    const val AGENT_STATS_DIALOG = "agent.stats.dialog"
    const val AGENT_STATS_TIME = "agent.stats.time"
    const val AGENT_STATS_COST = "agent.stats.cost"
    const val AGENT_STATS_PROMPTS = "agent.stats.prompts"
    const val AGENT_STATS_CLOSE = "agent.stats.close"
    const val AGENT_TEMPLATES = "agent.templates"
    const val AGENT_TEMPLATE_NEW = "agent.template.new"
    const val AGENT_TEMPLATE_NAME = "agent.template.name"
    const val AGENT_TEMPLATE_TEXT = "agent.template.text"
    const val AGENT_TEMPLATE_SAVE = "agent.template.save"
    const val AGENT_TEMPLATE_DELETE = "agent.template.delete"
    const val AGENT_TEMPLATE_USE = "agent.template.use"
    const val AGENT_TEMPLATE_GLOBAL = "agent.template.global"
    const val AGENT_TEMPLATE_PROJECT = "agent.template.project"

    /** The button beside the templates button that opens the teams of agents. */
    const val AGENT_TEAMS = "agent.teams"
    const val AGENT_TEAMS_DIALOG = "agent.teams.dialog"
    const val AGENT_TEAMS_LIST = "agent.teams.list"
    const val AGENT_TEAMS_EMPTY = "agent.teams.empty"
    const val AGENT_TEAMS_CLOSE = "agent.teams.close"
    const val AGENT_TEAM_NEW = "agent.team.new"
    const val AGENT_TEAM_FORM = "agent.team.form"
    const val AGENT_TEAM_NAME = "agent.team.name"
    const val AGENT_TEAM_GLOBAL = "agent.team.global"
    const val AGENT_TEAM_PROJECT = "agent.team.project"
    const val AGENT_TEAM_ADD_MEMBER = "agent.team.addMember"
    const val AGENT_TEAM_SAVE = "agent.team.save"
    const val AGENT_TEAM_CANCEL = "agent.team.cancel"

    /** Why the form of a team cannot be saved yet. */
    const val AGENT_TEAM_PROBLEM = "agent.team.problem"

    /** The picker of the team of a conversation, in the prompt bar. */
    const val AGENT_TEAM_PICKER = "agent.teamPicker"

    /** Its entry "No team". */
    const val AGENT_TEAM_PICKER_NONE = "agent.teamPicker.none"

    /** Its entry "Manage teams…", which opens the Teams window. */
    const val AGENT_TEAM_PICKER_MANAGE = "agent.teamPicker.manage"

    /** Its list of teams, a lazy list: only the rows shown are composed. */
    const val AGENT_TEAM_PICKER_LIST = "agent.teamPicker.list"

    /** The notice of a started conversation whose team was deleted. */
    const val AGENT_TEAM_MISSING = "agent.teamPicker.missing"

    /** The role of the manager in the tags of the fields of a team. */
    const val TEAM_MANAGER = "manager"

    /** The role of the member at [index] in the tags of the fields of a team. */
    fun teamMember(index: Int) = "member.$index"

    /** A team in the menu of the picker, by its id. */
    fun agentTeamChoice(id: String) = "agent.teamPicker.team.$id"

    /** A team of the list, by its id. */
    fun agentTeam(id: String) = "agent.team.row.$id"

    fun agentTeamEdit(id: String) = "agent.team.edit.$id"

    fun agentTeamDuplicate(id: String) = "agent.team.duplicate.$id"

    fun agentTeamDelete(id: String) = "agent.team.delete.$id"

    /** A field of the agent of [role] (`manager`, `member.0`): `name`, `description`, `provider`, `model`, `effort`, `skills`, `prompt`, `remove`. */
    fun agentTeamField(role: String, field: String) = "agent.team.$role.$field"

    /** A value of the menu of a field of the agent of [role], by its value (a model, a skill's path, a provider's id). */
    fun agentTeamValue(role: String, field: String, value: String) = "agent.team.$role.$field:$value"

    fun agentQuotaLimit(index: Int) = "agent.quota.$index"

    fun agentCommand(name: String) = "agent.command.$name"

    fun agentTemplateVariable(name: String) = "agent.template.variable.$name"

    fun agentImagePreview(name: String) = "agent.image.preview.$name"


    fun agentDetailChat(id: Int) = "agent.detail.chat.$id"

    fun agentTemplate(name: String) = "agent.template.$name"

    const val AGENT_LOGIN_RUNNING = "agent.login.running"
    const val AGENT_CHANGES_TOGGLE = "agent.changes.toggle"
    const val AGENT_CHANGES_REFRESH = "agent.changes.refresh"
    const val AGENT_CHANGES_REVERT_ALL = "agent.changes.revertAll"

    fun agentChanges(id: Int) = "agent.changes.$id"

    fun agentChange(path: String) = "agent.change:$path"

    fun agentChangeRevert(path: String) = "agent.change.revert:$path"



    const val AGENT_RENAME = "agent.rename"
    const val AGENT_RENAME_FIELD = "agent.renameField"
    const val AGENT_TABS = "agent.tabs"
    const val AGENT_EMPTY = "agent.empty"
    const val AGENT_PROMPT = "agent.prompt"
    const val AGENT_SEND = "agent.send"
    const val AGENT_STOP = "agent.stop"
    const val AGENT_THINKING = "agent.thinking"
    const val AGENT_NAME = "agent.name"
    const val AGENT_AGENT = "agent.agent"
    const val AGENT_AGENT_DEFAULT = "agent.agent.default"
    const val AGENT_AGENT_FILTER = "agent.agent.filter"
    const val AGENT_REGISTRY = "agent.registry"
    const val AGENT_MODEL = "agent.model"
    const val AGENT_PERMISSIONS = "agent.permissions"
    const val AGENT_EFFORT = "agent.effort"
    const val AGENT_COMPLETION = "agent.completion"
    const val AGENT_TERMINAL = "agent.terminal"

    /** The link that folds and unfolds a run of tool calls. */
    const val AGENT_TOOLS_TOGGLE = "agent.tools.toggle"

    /** The change a tool call makes to a file: its first lines. */
    const val AGENT_DIFF = "agent.diff"

    /** How many more lines the change has than its card shows. */
    const val AGENT_DIFF_MORE = "agent.diff.more"
    const val AGENT_STRIP_CONVERSATIONS = "agent.strip.conversations"
    const val AGENT_DETAILS = "agent.details"
    const val AGENT_DETAIL_CONVERSATION = "agent.detail.conversation"
    const val AGENT_DETAIL_EDITOR = "agent.detail.editor"
    const val AGENT_WORKSPACE_STATUS = "agent.workspaceStatus"
    const val AGENT_MODE_DIRECT = "agent.mode.direct"
    const val AGENT_MODE_WORKTREE = "agent.mode.worktree"

    fun settingsAgent(id: String) = "settings.agent:$id"

    fun agentConversation(id: Int) = "agent.conversation.$id"

    fun agentTab(id: Int) = "agent.tab.$id"

    fun agentTabClose(id: Int) = "agent.tab.$id.close"

    const val AGENT_LIST_SPLITTER = "agent.listSplitter"
    const val AGENT_LIST_HEIGHT_SPLITTER = "agent.listHeightSplitter"
    const val AGENT_COLUMN_SCROLLBAR = "agent.columnScrollbar"
    const val AGENT_LIST_BAR = "agent.listBar"
    const val AGENT_LOCATION_CARD = "agent.locationCard"
    const val AGENT_INFO_BRANCH = "agent.info.branch"
    const val AGENT_INFO_PATH = "agent.info.path"
    const val AGENT_INFO_MODE = "agent.info.mode"
    const val AGENT_INFO_BASE = "agent.info.base"
    const val AGENT_INFO_GIT = "agent.info.git"
    const val AGENT_INFO_GIT_ERROR = "agent.info.gitError"
    const val AGENT_INFO_COMMIT = "agent.info.commit"
    const val AGENT_INFO_FILES = "agent.info.files"
    const val AGENT_INFO_TERMINAL = "agent.info.terminal"
    const val AGENT_GIT_ACTIONS = "agent.gitActions"

    fun agentGitAction(name: String) = "agent.gitAction:$name"
    const val AGENT_BASE_PICKER = "agent.basePicker"
    const val AGENT_BASE_SEARCH = "agent.basePicker.search"
    const val AGENT_BASE_PROJECT = "agent.basePicker.project"
    const val AGENT_BASE_ERROR = "agent.basePicker.error"
    const val AGENT_BASE_FETCHING = "agent.basePicker.fetching"
    const val AGENT_BASE_FETCH_ERROR = "agent.basePicker.fetchError"

    fun agentBase(branch: String) = "agent.basePicker.branch:$branch"
    const val AGENT_BASE_OTHER_PROJECT = "agent.basePicker.otherProject"
    const val AGENT_BASE_BROWSE = "agent.basePicker.browse"

    fun agentBaseProject(path: String) = "agent.basePicker.projectChoice:$path"
    const val AGENT_LAST_MESSAGE = "agent.lastMessage"

    fun agentConversationAgent(id: Int) = "agent.conversation.$id.agent"

    fun agentWorking(id: Int) = "agent.working.$id"

    fun agentDone(id: Int) = "agent.done.$id"

    fun agentAsking(id: Int) = "agent.asking.$id"

    fun agentFailed(id: Int) = "agent.failed.$id"

    fun agentSeen(id: Int) = "agent.seen.$id"

    fun agentTabDetails(id: Int) = "agent.tab.$id.details"

    fun agentView(id: Int) = "agent.view.$id"

    fun agentMessage(index: Int) = "agent.message.$index"

    /** An entry of the menu of the agent of the conversation, by its registry id (`fake-agent`). */
    fun agentAgent(id: String) = "agent.agent:$id"

    /** A model of the menu of the model of the session, by its value (`fake-smart`). */
    fun agentModel(value: String) = "agent.model:$value"

    /** A mode of the menu of the mode of permissions of the session, by its value (`accept-edits`). */
    fun agentPermissionMode(value: String) = "agent.permissions:$value"

    /** An effort of the menu of the effort of the session, by its value (`high`). */
    fun agentEffort(value: String) = "agent.effort:$value"

    /** A suggestion of the completion of the prompt, by its label (`/echo`, `src/Main.kt`). */
    fun agentCompletion(label: String) = "agent.completion:$label"

    /** Something joined to the prompt, by its label (`Main.kt`, `Main.kt:3-7`, `image-1.png`). */
    fun agentAttachment(label: String) = "agent.attachment:$label"

    /** The button that removes something joined to the prompt, by its label. */
    fun agentAttachmentRemove(label: String) = "agent.attachment.remove:$label"

    /** Something joined to a prompt sent, as the transcript shows it, by its label. */
    fun agentSentAttachment(label: String) = "agent.sent.attachment:$label"

    /** A button of the pending permission request, by the id of its option (`allow`). */
    fun agentPermission(option: String) = "agent.permission:$option"
}
