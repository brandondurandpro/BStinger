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

    /** The group of the conversations that work in no project. */
    const val AGENT_SCRATCH_GROUP = "agent.group.scratch"

    /** The group of the conversations that work in the folder [path], which is not the project of the window. */
    fun agentFolderGroup(path: String) = "agent.group.folder:$path"

    /** The button that removes the group of the folder [path] from the list. */
    fun agentFolderRemove(path: String) = "agent.group.folder.remove:$path"
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

    /** The buttons that remove the group of the project, and the group without a project, from the list. */
    const val AGENT_PROJECT_REMOVE = "agent.group.project.remove"
    const val AGENT_SCRATCH_REMOVE = "agent.group.scratch.remove"

    /** The button of the Teams window that opens the skill catalog, and the parts of the catalog window. */
    const val AGENT_SKILL_CATALOG = "agent.catalog.open"
    const val AGENT_CATALOG_DIALOG = "agent.catalog"
    const val AGENT_CATALOG_CLOSE = "agent.catalog.close"
    const val AGENT_CATALOG_REFRESH = "agent.catalog.refresh"
    const val AGENT_CATALOG_FILTER = "agent.catalog.filter"
    const val AGENT_CATALOG_LIST = "agent.catalog.list"
    const val AGENT_CATALOG_LOADING = "agent.catalog.loading"
    const val AGENT_CATALOG_ERROR = "agent.catalog.error"
    const val AGENT_CATALOG_EMPTY = "agent.catalog.empty"

    fun agentCatalogSource(source: String) = "agent.catalog.source:$source"

    fun agentCatalogSourceError(source: String) = "agent.catalog.sourceError:$source"

    fun agentCatalogSkill(source: String, path: String) = "agent.catalog.skill:$source:$path"

    fun agentCatalogInstall(source: String, path: String) = "agent.catalog.install:$source:$path"

    fun agentCatalogInstalled(source: String, path: String) = "agent.catalog.installed:$source:$path"

    fun agentCatalogInstallError(source: String, path: String) = "agent.catalog.installError:$source:$path"

    /** The Teams button at the top left of the list of conversations. */
    const val AGENT_LIST_TEAMS = "agent.list.teams"

    /** The cross at the top right of the Teams window. */
    const val AGENT_TEAMS_CLOSE_CROSS = "agent.teams.closeCross"
    const val AGENT_TEAM_NEW = "agent.team.new"
    const val AGENT_TEAM_STARTER = "agent.team.starter"
    const val AGENT_TEAM_FORM = "agent.team.form"
    const val AGENT_TEAM_NAME = "agent.team.name"
    const val AGENT_TEAM_GLOBAL = "agent.team.global"
    const val AGENT_TEAM_PROJECT = "agent.team.project"
    const val AGENT_TEAM_ADD_MEMBER = "agent.team.addMember"
    const val AGENT_TEAM_SAVE = "agent.team.save"
    const val AGENT_TEAM_CANCEL = "agent.team.cancel"

    /** Why the form of a team cannot be saved yet. */
    const val AGENT_TEAM_PROBLEM = "agent.team.problem"

    /** The folder a conversation works in, in the prompt bar, and its menu. */
    const val AGENT_FOLDER = "agent.folder"
    const val AGENT_FOLDER_PATH = "agent.folder.path"
    const val AGENT_FOLDER_ROOT = "agent.folder.root"
    const val AGENT_FOLDER_APPLY = "agent.folder.apply"

    /** In the menu of the folder of a conversation without a project: the project of this window, and the others. */
    const val AGENT_FOLDER_THIS_PROJECT = "agent.folder.thisProject"

    fun agentFolderProject(path: String) = "agent.folder.project:$path"
    const val AGENT_FOLDER_BROWSE = "agent.folder.browse"
    const val AGENT_FOLDER_ERROR = "agent.folder.error"
    const val AGENT_FOLDER_LIST = "agent.folder.list"

    fun agentFolderChoice(path: String) = "agent.folder.choice:$path"

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

    /** The view of a member of the team of a conversation, shown by its tab: its status and its transcript. */
    const val AGENT_MEMBER_VIEW = "agent.member.view"

    /** The status of the member shown, in words. */
    const val AGENT_MEMBER_STATUS = "agent.member.status"

    /** The transcript of the member shown, a lazy list. */
    const val AGENT_MEMBER_TRANSCRIPT = "agent.member.transcript"

    /** What the view of a member says while it has no transcript to show. */
    const val AGENT_MEMBER_EMPTY = "agent.member.empty"

    /** The card of the delegation [id]: a task the manager gave a member. */
    fun agentDelegation(id: String) = "agent.delegation.$id"

    /** Its header, which expands the card and folds it back. */
    fun agentDelegationToggle(id: String) = "agent.delegation.toggle.$id"

    /** Its status, in words. */
    fun agentDelegationStatus(id: String) = "agent.delegation.status.$id"

    /** The report of its member, once the task ended. */
    fun agentDelegationReport(id: String) = "agent.delegation.report.$id"

    /** The work of its member, a lazy list, shown while the card is expanded. */
    fun agentDelegationWork(id: String) = "agent.delegation.work.$id"

    /** What an expanded card says instead of the work: it loads, or there is none. */
    fun agentDelegationNote(id: String) = "agent.delegation.note.$id"

    /** The row [index] of the work of a member, in a card or in its tab. */
    fun agentMemberRow(index: Int) = "agent.member.row.$index"

    /** The question or permission text of the request [id] of [asker] ("Agent" for the conversation's own, else a member). */
    fun agentRequestTitle(asker: String, id: String) = "agent.request.title.$asker.$id"

    /** What the asker wrote before its question: the proposals the user decides on. */
    fun agentRequestSaid(asker: String, id: String) = "agent.request.said.$asker.$id"

    /** The answer a request [id] got, once it was answered. */
    fun agentRequestAnswer(asker: String, id: String) = "agent.request.answer.$asker.$id"

    /** The button of the choice [choice] a member proposes in its question [id]. */
    fun agentQuestionChoice(asker: String, id: String, choice: String) = "agent.question.choice.$asker.$id.$choice"

    /** The field where the user answers the question [id] in their own words. */
    fun agentQuestionText(asker: String, id: String) = "agent.question.text.$asker.$id"

    /** The button that sends the words typed in the field of the question [id]. */
    fun agentQuestionSend(asker: String, id: String) = "agent.question.send.$asker.$id"

    /** The permission request of the member named [member], at the end of the conversation of its team. */
    fun agentMemberRequest(member: String) = "agent.member.request.$member"

    /** The tab of the member [id] of the team, among the detail tabs of the conversation. */
    fun agentMemberTab(id: String) = "agent.member.tab.$id"

    /** The status this tab shows (`idle`, `working`, `failed`). */
    fun agentMemberTabStatus(id: String) = "agent.member.tab.status.$id"

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

    /** The tab of the agent of [role] (`manager`, `member.0`) in the form of a team: it shows the fields of that agent. */
    fun agentTeamTab(role: String) = "agent.team.tab.$role"

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
