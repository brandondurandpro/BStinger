package org.example.kotlineditor.ui

/** Semantics test tags of agent mode (its switch, conversations, prompt, settings), shared with the end-to-end tests. */
object AgentTags {
    const val SETTINGS_AGENTS = "settings.agents"
    const val MODE_SWITCH = "mode.switch"
    const val MODE_IDE = "mode.ide"
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
    const val AGENT_REVIEW_FILTER = "agent.review.filter"
    const val AGENT_ARCHIVED = "agent.archived"
    const val AGENT_USAGE = "agent.usage"
    const val AGENT_QUOTA = "agent.quota"
    const val AGENT_COMMANDS = "agent.commands"
    const val AGENT_COMMANDS_FILTER = "agent.commands.filter"
    const val AGENT_COST = "agent.cost"
    const val AGENT_QUOTA_CARD = "agent.quota.card"
    const val AGENT_CARD_CONTEXT = "agent.quota.card.context"
    const val AGENT_CARD_COST = "agent.quota.card.cost"
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

    fun agentQuotaLimit(index: Int) = "agent.quota.$index"

    fun agentCommand(name: String) = "agent.command.$name"

    fun agentTemplateVariable(name: String) = "agent.template.variable.$name"

    fun agentTemplate(name: String) = "agent.template.$name"

    const val AGENT_LOGIN_RUNNING = "agent.login.running"
    const val AGENT_CHANGES_TOGGLE = "agent.changes.toggle"
    const val AGENT_CHANGES_REFRESH = "agent.changes.refresh"
    const val AGENT_CHANGES_REVERT_ALL = "agent.changes.revertAll"

    fun agentChanges(id: Int) = "agent.changes.$id"

    fun agentChange(path: String) = "agent.change:$path"

    fun agentChangeRevert(path: String) = "agent.change.revert:$path"

    const val AGENT_REVIEW_DEFAULT = "agent.review.default"

    fun agentReviewer(id: String) = "agent.review.$id"

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
